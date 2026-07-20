package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        if (subscriptions.isEmpty()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        val now = System.currentTimeMillis() / 1000L
        val schedule = calculateSubscriptionSchedule(
            nowSeconds = now,
            timings = subscriptions.map { group ->
                group.subscription!!.let { subscription ->
                    SubscriptionTiming(
                        lastUpdatedSeconds = subscription.lastUpdated.toLong(),
                        intervalMinutes = subscription.autoUpdateDelay,
                    )
                }
            },
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(
                UpdateTask::class.java,
                schedule.repeatIntervalMinutes,
                TimeUnit.MINUTES,
            )
                .setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            var failed = false
            try {
                if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                    val subscription = profile.subscription!!

                    if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                        Logs.d("work: subscription ${profile.id} is not due")
                        continue
                    }
                    Logs.d("work: updating subscription ${profile.id}")

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    nm.notify(2, notification.build())

                    if (!GroupUpdater.executeUpdate(profile, false)) failed = true
                }
            } finally {
                nm.cancel(2)
            }

            return if (failed) Result.retry() else Result.success()
        }
    }

}

internal data class SubscriptionTiming(
    val lastUpdatedSeconds: Long,
    val intervalMinutes: Int,
)

internal data class SubscriptionWorkSchedule(
    val repeatIntervalMinutes: Long,
    val initialDelaySeconds: Long,
)

internal fun calculateSubscriptionSchedule(
    nowSeconds: Long,
    timings: List<SubscriptionTiming>,
): SubscriptionWorkSchedule {
    require(timings.isNotEmpty()) { "At least one subscription is required" }
    val repeatIntervalMinutes = timings.minOf {
        it.intervalMinutes.coerceAtLeast(15).toLong()
    }
    val initialDelaySeconds = timings.minOf { timing ->
        val intervalSeconds = timing.intervalMinutes.coerceAtLeast(15).toLong() * 60L
        (timing.lastUpdatedSeconds + intervalSeconds - nowSeconds).coerceAtLeast(0L)
    }
    return SubscriptionWorkSchedule(repeatIntervalMinutes, initialDelaySeconds)
}
