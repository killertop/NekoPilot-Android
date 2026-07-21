package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        val subscriptions = autoUpdateSubscriptions(SagerDatabase.groupDao.subscriptions())
        if (subscriptions.isEmpty()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        val now = System.currentTimeMillis() / 1000L
        val schedule = calculateSubscriptionSchedule(
            nowSeconds = now,
            timings = subscriptions.map { (_, subscription) ->
                SubscriptionTiming(
                    lastUpdatedSeconds = subscription.lastUpdated.toLong(),
                    intervalMinutes = subscription.autoUpdateDelay,
                )
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

        override suspend fun doWork(): Result {
            try {
                io.nekohasekai.sagernet.SagerNet.application.ensureCoreInitialized()
                var subscriptions = autoUpdateSubscriptions(
                    SagerDatabase.groupDao.subscriptions()
                )
                if (!DataStore.serviceState.connected) {
                    Logs.d("work: not connected")
                    subscriptions = subscriptions
                        .filter { (_, subscription) -> !subscription.updateWhenConnectedOnly }
                }

                var failed = false
                if (subscriptions.isNotEmpty()) for ((profile, subscription) in subscriptions) {

                    if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                        Logs.d("work: subscription ${profile.id} is not due")
                        continue
                    }
                    Logs.d("work: updating subscription ${profile.id}")

                    if (!GroupUpdater.executeUpdate(profile, false)) failed = true
                }
                return if (failed) Result.retry() else Result.success()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Core/native initialization and transient database failures are retryable. Keep
                // them inside the worker boundary so WorkManager applies backoff instead of
                // reporting a permanently failed invocation.
                android.util.Log.w(
                    "SubscriptionUpdater",
                    "Worker failed (${error.javaClass.simpleName})",
                )
                return Result.retry()
            }
        }
    }

}

internal fun autoUpdateSubscriptions(
    groups: List<ProxyGroup>,
): List<Pair<ProxyGroup, SubscriptionBean>> = groups.mapNotNull { group ->
    group.subscription
        ?.takeIf(SubscriptionBean::autoUpdate)
        ?.let { subscription -> group to subscription }
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
