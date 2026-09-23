package com.nora.douyinremover.updater

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 版本检测 Worker：双源检测（Gitee 优先），结果写入 UpdateRepository。
 * - 周期任务：每 5 小时，需网络连通，指数退避重试
 * - 冷启动：额外触发一次即时检测（OneTime），国产 ROM 杀后台时兜底
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val checker = UpdateChecker()
            val info = checker.checkLatest()
            if (info != null) {
                val current = applicationContext.packageManager
                    .getPackageInfo(applicationContext.packageName, 0).versionName ?: "0.0.0"
                val repo = UpdateRepository(applicationContext)
                if (UpdateChecker.compareVersions(current, info.latestVersion) > 0) {
                    repo.save(info, current)
                    Log.i(TAG, "update available: $current -> ${info.latestVersion} (${info.source})")
                } else {
                    // 已是最新：清掉旧提醒
                    repo.clear()
                    Log.i(TAG, "up to date: $current")
                }
                Result.success()
            } else {
                Log.w(TAG, "both sources failed")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val TAG = "UpdateCheckWorker"
        private const val PERIODIC_WORK = "update_check_periodic"
        private const val ONESHOT_WORK = "update_check_oneshot"
        private const val PERIOD_HOURS = 5L

        /** 注册 5 小时周期检测（幂等，App 启动时调用） */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** 立即触发一次检测（冷启动兜底；REPLACE 保证不堆积） */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
