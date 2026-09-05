package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.R

/**
 * Disconnect / reconnect straight from the notification, so the tunnel can be
 * cycled without opening the app.
 *
 * Disconnect is a plain broadcast, the same one the in app button sends, and is
 * picked up by [CloseModule]. Reconnect has to bounce through
 * ExternalControlActivity instead: bringing the tunnel back up needs the vpn
 * permission check and a wait for the old service to actually die.
 */
fun NotificationCompat.Builder.addControlActions(service: Service): NotificationCompat.Builder =
    addAction(
        0,
        service.getText(R.string.notification_disconnect),
        PendingIntent.getBroadcast(
            service,
            R.id.nf_clash_disconnect,
            Intent(Intents.ACTION_CLASH_REQUEST_STOP).setPackage(service.packageName),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    ).addAction(
        0,
        service.getText(R.string.notification_reconnect),
        PendingIntent.getActivity(
            service,
            R.id.nf_clash_reconnect,
            Intent(Intents.ACTION_RESTART_CLASH)
                .setComponent(Components.EXTERNAL_CONTROL_ACTIVITY)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    )
