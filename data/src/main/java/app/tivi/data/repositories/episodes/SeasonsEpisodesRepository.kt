/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.data.repositories.episodes

import app.tivi.data.entities.Episode
import app.tivi.data.entities.PendingAction
import app.tivi.data.entities.Season
import app.tivi.inject.Tmdb
import app.tivi.inject.Trakt
import app.tivi.trakt.TraktAuthState
import app.tivi.util.AppCoroutineDispatchers
import kotlinx.coroutines.experimental.async
import javax.inject.Inject
import javax.inject.Provider

class SeasonsEpisodesRepository @Inject constructor(
    private val dispatchers: AppCoroutineDispatchers,
    private val localStore: LocalSeasonsEpisodesStore,
    @Trakt private val traktSeasonsDataSource: SeasonsEpisodesDataSource,
    @Trakt private val traktEpisodeDataSource: EpisodeDataSource,
    @Tmdb private val tmdbEpisodeDataSource: EpisodeDataSource,
    private val traktAuthState: Provider<TraktAuthState>
) {
    fun observeSeasonsForShow(showId: Long) = localStore.observeShowSeasonsWithEpisodes(showId)

    fun observeEpisode(episodeId: Long) = localStore.observeEpisode(episodeId)

    fun observeEpisodeWatches(episodeId: Long) = localStore.observeEpisodeWatches(episodeId)

    suspend fun updateSeasonsEpisodes(showId: Long) {
        traktSeasonsDataSource.getSeasonsEpisodes(showId)
                .map { (traktSeason, episodes) ->
                    val localSeason = localStore.getSeasonWithTraktId(traktSeason.traktId!!)
                            ?: Season(showId = showId)
                    val mergedSeason = mergeSeason(localSeason, traktSeason, Season.EMPTY)

                    val mergedEpisodes = episodes.map {
                        val localEpisode = localStore.getEpisodeWithTraktId(it.traktId!!)
                                ?: Episode(seasonId = mergedSeason.showId)
                        mergeEpisode(localEpisode, it, Episode.EMPTY)
                    }
                    (mergedSeason to mergedEpisodes)
                }
                .also {
                    // Save the seasons + episodes
                    localStore.save(it)
                }
    }

    suspend fun updateEpisode(episodeId: Long) {
        val local = localStore.getEpisode(episodeId)!!
        val season = localStore.getSeason(local.seasonId)!!
        val trakt = async(dispatchers.io) {
            traktEpisodeDataSource.getEpisode(season.showId, season.number!!, local.number!!) ?: Episode.EMPTY
        }
        val tmdb = async(dispatchers.io) {
            tmdbEpisodeDataSource.getEpisode(season.showId, season.number!!, local.number!!) ?: Episode.EMPTY
        }

        localStore.save(mergeEpisode(local, trakt.await(), tmdb.await()))
    }

    suspend fun syncEpisodeWatches(showId: Long) {
        processPendingDelete(showId)
        processPendingAdditions(showId)
        if (traktAuthState.get() == TraktAuthState.LOGGED_IN) {
            refreshWatchesFromRemote(showId)
        }
    }

    private suspend fun processPendingAdditions(showId: Long) {
        val entries = localStore.getEntriesWithAddAction(showId)
        if (entries.isNotEmpty() && traktAuthState.get() == TraktAuthState.LOGGED_IN) {
            traktSeasonsDataSource.addEpisodeWatches(entries)
        }
        // Now update the database
        localStore.updateWatchEntriesWithAction(entries.mapNotNull { it.id }, PendingAction.NOTHING)
    }

    private suspend fun processPendingDelete(showId: Long) {
        val entries = localStore.getEntriesWithDeleteAction(showId)
        if (entries.isNotEmpty() && traktAuthState.get() == TraktAuthState.LOGGED_IN) {
            traktSeasonsDataSource.removeEpisodeWatches(entries)
        }
        // Now update the database
        localStore.deleteWatchEntriesWithIds(entries.mapNotNull { it.id })
    }

    private suspend fun refreshWatchesFromRemote(showId: Long) {
        traktSeasonsDataSource.getShowEpisodeWatches(showId)
                .map { (episode, watchEntry) ->
                    // Grab the episode id if it exists, or save the episode and use it's generated ID
                    val episodeId = localStore.getEpisodeIdOrSavePlaceholder(episode)
                    watchEntry.copy(episodeId = episodeId)
                }
                .also { localStore.syncWatchEntries(showId, it) }
    }

    private fun mergeSeason(local: Season, trakt: Season, tmdb: Season) = local.copy(
            title = trakt.title ?: local.title,
            summary = trakt.summary ?: local.summary,
            number = trakt.number ?: local.number,

            network = trakt.network ?: tmdb.network ?: local.network,
            episodeCount = trakt.episodeCount ?: tmdb.episodeCount ?: local.episodeCount,
            episodesAired = trakt.episodesAired ?: tmdb.episodesAired ?: local.episodesAired,

            // Trakt specific stuff
            traktId = trakt.traktId ?: local.traktId,
            traktRating = trakt.traktRating ?: local.traktRating,
            traktRatingVotes = trakt.traktRatingVotes ?: local.traktRatingVotes,

            // TMDb specific stuff
            tmdbId = tmdb.tmdbId ?: trakt.tmdbId ?: local.tmdbId,
            tmdbPosterPath = tmdb.tmdbPosterPath ?: local.tmdbPosterPath,
            tmdbBackdropPath = tmdb.tmdbBackdropPath ?: local.tmdbBackdropPath
    )

    private fun mergeEpisode(local: Episode, trakt: Episode, tmdb: Episode) = local.copy(
            title = trakt.title ?: tmdb.title ?: local.title,
            summary = trakt.summary ?: tmdb.summary ?: local.summary,
            number = trakt.number ?: tmdb.number ?: local.number,
            firstAired = trakt.firstAired ?: tmdb.firstAired ?: local.firstAired,

            // Trakt specific stuff
            traktId = trakt.traktId ?: local.traktId,
            traktRating = trakt.traktRating ?: local.traktRating,
            traktRatingVotes = trakt.traktRatingVotes ?: local.traktRatingVotes,

            // TMDb specific stuff
            tmdbId = tmdb.tmdbId ?: trakt.tmdbId ?: local.tmdbId,
            tmdbBackdropPath = tmdb.tmdbBackdropPath ?: local.tmdbBackdropPath
    )
}