package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.PixelPlayerApplication
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromeRepository
import com.lostf1sh.pixelplayeross.data.offline.NavidromeOfflineManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class NavidromeDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Injecting NavidromeRepository forces its init block to run on this worker's
    // (previously repository-less) Hilt graph, restoring saved credentials into the
    // shared NavidromeApiService before the manager tries to resolve stream URLs.
    private val repository: NavidromeRepository,
    private val manager: NavidromeOfflineManager
) : CoroutineWorker(appContext, workerParams) {

    @Volatile private var lastDone = 0
    @Volatile private var lastTotal = 0
    @Volatile private var lastTitle: String? = null

    override suspend fun doWork(): Result {
        if (!repository.isLoggedIn) return Result.success()
        return try {
            val drained = manager.drainDownloads { done, total, currentTitle ->
                lastDone = done
                lastTotal = total
                lastTitle = currentTitle
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                try {
                    setForeground(buildForegroundInfo(done, total, currentTitle))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Background-start foreground-service restrictions can reject this on
                    // some OEMs/Android versions; the download itself must keep going.
                    Timber.d(e, "NavidromeDownloadWorker: setForeground failed, continuing without it")
                }
            }
            if (drained) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "NavidromeDownloadWorker: drain failed")
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(lastDone, lastTotal, lastTitle)

    private fun buildForegroundInfo(done: Int, total: Int, currentTitle: String?): ForegroundInfo {
        val context = applicationContext
        val text = if (currentTitle != null) {
            context.getString(R.string.download_notification_progress_with_title, currentTitle, done, total)
        } else {
            context.getString(R.string.download_notification_progress, done, total)
        }
        val notification = NotificationCompat.Builder(context, PixelPlayerApplication.DOWNLOADS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.monochrome_player)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val NOTIFICATION_ID = 4200
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
    }
}
