package org.aurora.protocol.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build

internal fun AuroraVpnService.enterVpnForeground() {
    val manager = getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
            AuroraVpnService.notificationChannel,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
    manager.createNotificationChannel(channel)
    val disconnectAction = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_aurora),
        getString(R.string.action_disconnect),
        PendingIntent.getService(
            this,
            AuroraVpnService.disconnectRequestCode,
            Intent(this, AuroraVpnService::class.java).setAction(AuroraVpnService.actionDisconnect),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setAuthenticationRequired(true)
        }
    }.build()
    val builder = Notification.Builder(this, AuroraVpnService.notificationChannel)
        .setSmallIcon(R.drawable.ic_aurora)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_active))
        .setCategory(Notification.CATEGORY_SERVICE)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setLocalOnly(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                AuroraVpnService.openAppRequestCode,
                Intent(this, AuroraActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(disconnectAction)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }
    val notification = builder.build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
            AuroraVpnService.notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    } else {
        startForeground(AuroraVpnService.notificationId, notification)
    }
}
