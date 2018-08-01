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

package app.tivi.showdetails.episodedetails

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import app.tivi.data.daos.EpisodeWatchEntryDao
import app.tivi.data.daos.EpisodesDao
import app.tivi.data.entities.EpisodeWatchEntry
import app.tivi.data.entities.PendingAction
import app.tivi.interactors.SyncFollowedShowWatchedProgress
import app.tivi.interactors.UpdateEpisodeDetails
import app.tivi.interactors.UpdateEpisodeWatches
import app.tivi.showdetails.episodedetails.EpisodeDetailsViewState.Action
import app.tivi.tmdb.TmdbManager
import app.tivi.util.AppCoroutineDispatchers
import app.tivi.util.Logger
import app.tivi.util.TiviViewModel
import io.reactivex.Flowable
import io.reactivex.rxkotlin.Flowables
import io.reactivex.rxkotlin.plusAssign
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.format.DateTimeFormatter
import javax.inject.Inject

class EpisodeDetailsViewModel @Inject constructor(
    private val updateEpisodeDetails: UpdateEpisodeDetails,
    private val updateEpisodeWatches: UpdateEpisodeWatches,
    private val tmdbManager: TmdbManager,
    private val logger: Logger,
    private val episodesDao: EpisodesDao,
    private val episodeWatchEntryDao: EpisodeWatchEntryDao,
    private val dispatchers: AppCoroutineDispatchers,
    private val dateTimeFormatter: DateTimeFormatter,
    private val syncFollowedShowWatchedProgress: SyncFollowedShowWatchedProgress
) : TiviViewModel() {

    var episodeId: Long? = null
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    private val _data = MutableLiveData<EpisodeDetailsViewState>()
    val data: LiveData<EpisodeDetailsViewState>
        get() = _data

    init {
        setupLiveData()
    }

    private fun refresh() {
        val epId = episodeId
        if (epId != null) {
            launchInteractor(updateEpisodeDetails, UpdateEpisodeDetails.Params(epId, true))
            launchInteractor(updateEpisodeWatches, UpdateEpisodeWatches.Params(epId, true))
        } else {
            _data.value = null
        }
    }

    private fun setupLiveData() {
        disposables.clear()

        val watches = updateEpisodeWatches.observe()

        disposables += Flowables.combineLatest(
                updateEpisodeDetails.observe(),
                watches,
                tmdbManager.imageProvider,
                watches.map {
                    if (it.isEmpty()) { Action.WATCH } else { Action.UNWATCH }
                },
                Flowable.just(dateTimeFormatter),
                ::EpisodeDetailsViewState
        ).subscribe(_data::postValue, logger::e)
    }

    fun markWatched() {
        val epId = episodeId!!
        launchWithParent(dispatchers.io) {
            val entry = EpisodeWatchEntry(
                    episodeId = episodeId!!,
                    watchedAt = OffsetDateTime.now(),
                    pendingAction = PendingAction.UPLOAD
            )
            episodeWatchEntryDao.insert(entry)

            syncFollowedShowWatchedProgress(
                    SyncFollowedShowWatchedProgress.Params(episodesDao.showIdForEpisodeId(epId), true))
        }
    }

    fun markUnwatched() {
        val epId = episodeId!!
        launchWithParent(dispatchers.io) {
            val entries = episodeWatchEntryDao.watchesForEpisode(epId)
            entries.forEach {
                // We have a trakt id, so we need to do a sync
                if (it.pendingAction != PendingAction.DELETE) {
                    // If it is not set to be deleted, update it now
                    val copy = it.copy(pendingAction = PendingAction.DELETE)
                    episodeWatchEntryDao.update(copy)
                }
            }
            syncFollowedShowWatchedProgress(
                    SyncFollowedShowWatchedProgress.Params(episodesDao.showIdForEpisodeId(epId), true))
        }
    }
}