package ai.ridingrelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 내비 화면이 앞에 있는 동안에도 차량 신호를 받기 위한 포그라운드 서비스.
 * 없으면 시스템이 뒤로 간 앱을 멈춰서 문과 좌석 신호가 앱을 다시 열 때까지 밀린다.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "relay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "라이딩 진행", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            Notification.Builder(this, channel)
                .setContentTitle("라이딩 릴레이")
                .setContentText("하차 확인을 기다리는 중")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .build()
        )
        return START_STICKY
    }
}
