package com.paircar.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paircar.R

/**
 * "내비 위 팝업"의 공식 대안 후보: 표준 Android 알림.
 * IMPORTANCE_HIGH 채널은 일반 Android 에서 Heads-up 으로 표시되지만,
 * Pleos Connect 에서 서드파티 앱 알림이 Heads-up 으로 뜨는지는 공식 문서에 없다 → 에뮬레이터 검증 대상.
 * Pleos 디자인 가이드: 운전과 관련된 중요한 정보만, 간결하게, 광고 금지.
 */
class PickupNotifier(context: Context) {

    private val appContext = context.applicationContext

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_pickup),
                NotificationManager.IMPORTANCE_HIGH,
            )
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** @return 알림을 게시했으면 true (권한 없으면 false) */
    fun notifyPickupArrived(placeName: String): Boolean = post(
        id = PICKUP_ID,
        title = "픽업 지점 도착",
        text = "$placeName · 동승자 탑승을 확인하세요",
    )

    fun post(id: Int, title: String, text: String): Boolean {
        if (!canNotify()) return false
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_carpool)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canNotify() 로 확인
        NotificationManagerCompat.from(appContext).notify(id, notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "pickup"
        const val PICKUP_ID = 1001
        const val TEST_ID = 1002
    }
}
