package com.example.ridemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class RideService : Service() {
    companion object {
        const val ACTION_START = "com.example.ridemesh.START"
        const val ACTION_STOP = "com.example.ridemesh.STOP"
        const val EXTRA_KEY = "group_key"
        @Volatile var current: RideService? = null
            private set
    }

    @Volatile var stateText = "尚未啟動"
        private set
    @Volatile var directPeers = 0
        private set
    @Volatile var roomId = ""
        private set
    private var router: MeshRouter? = null
    private var nearby: NearbyMesh? = null
    private var audio: AudioEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ride", "車隊通話", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || router != null) return START_NOT_STICKY
        val groupKey = Wire.parseKey(intent.getStringExtra(EXTRA_KEY).orEmpty()) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(7, notification())
        try {
            val node = Wire.nodeId()
            val newAudio = AudioEngine(this) { audioBytes -> router?.sendLocal(audioBytes) }
            val newRouter = MeshRouter(
                groupKey, node,
                { endpoint, bytes -> nearby?.send(endpoint, bytes) },
                { origin, bytes -> newAudio.play(origin, bytes) },
                { count -> directPeers = count }
            )
            val newNearby = NearbyMesh(this, newRouter) { stateText = it }
            router = newRouter
            audio = newAudio
            nearby = newNearby
            roomId = newRouter.roomId
            newAudio.start()
            newNearby.start()
            stateText = "通話中，正在尋找同群手機"
        } catch (e: Exception) {
            stateText = "啟動失敗：${e.localizedMessage}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun setMuted(value: Boolean) {
        audio?.muted = value
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "ride")
            .setContentTitle("RideMesh 車隊通話中")
            .setContentText("點選以查看連線；騎乘前請確認安全帽耳機")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "結束", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        nearby?.stop()
        audio?.stop()
        nearby = null
        audio = null
        router = null
        directPeers = 0
        stateText = "通話已結束"
        current = null
        super.onDestroy()
    }
}
