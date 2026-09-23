package com.nora.douyinremover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 处理期间的前台服务保活。
 *
 * vivo OriginOS 等激进后台管控的系统会在应用切后台/息屏后几秒内冻结进程，
 * ONNX 推理线程随之停摆，表现为"进度条卡住不动"。
 * 处理期间挂起一个前台服务（带常驻通知），系统就不会冻结进程。
 */
class ProcessingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra(EXTRA_STOP, false) ?: false
        if (stop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "音频处理",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "人声分离处理进行中，保持应用运行" }
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在处理音频")
            .setContentText("人声分离进行中，请保持应用在前台")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_STOP = "stop"

        /** 处理开始时调用 */
        fun start(context: Context) {
            val intent = Intent(context, ProcessingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 处理结束（成功或失败）时调用 */
        fun stop(context: Context) {
            val intent = Intent(context, ProcessingForegroundService::class.java)
                .putExtra(EXTRA_STOP, true)
            context.startService(intent)
        }
    }
}
