package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromePlaylistSyncManager
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class NavidromePlaylistPushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: NavidromeRepository,
    private val playlistSyncManager: NavidromePlaylistSyncManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Injecting NavidromeRepository forces its init block to run on this worker's
        // (previously repository-less) Hilt graph, restoring saved credentials into the
        // shared NavidromeApiService before we drain the outbox.
        if (!repository.isLoggedIn) return Result.success()
        return try {
            if (playlistSyncManager.drainPendingPlaylistOps()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "NavidromePlaylistPushWorker: push failed")
            Result.retry()
        }
    }
}
