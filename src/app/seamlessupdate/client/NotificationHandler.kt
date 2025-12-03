/*
 * Copyright 2025 Amaan Qureshi <contact@amaanq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.seamlessupdate.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.Html

class NotificationHandler(private val service: Service) {
    private val notificationManager: NotificationManager = service.getSystemService(NotificationManager::class.java)
    private var phase: Phase = Phase.CHECK

    private enum class Phase {
        CHECK,
        DOWNLOAD,
        VERIFY,
        INSTALL
    }

    fun start() {
        phase = Phase.CHECK

        // Avoid cancelling persistent security settings preview notification
        for (id in 1..MAX_NOTIFICATION_ID_FOR_SERVICE) {
            notificationManager.cancel(id)
        }

        service.startForeground(
            NOTIFICATION_ID_PROGRESS,
            Notification
                .Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(pendingSettingsIntent)
                .setContentTitle(service.getString(R.string.notification_check_title))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showUpdatedNotification(channel: String) {
        val channelText = when (channel) {
            "stable" -> service.getString(R.string.channel_stable)
            "beta" -> service.getString(R.string.channel_beta)
            "alpha" -> service.getString(R.string.channel_alpha)
            else -> channel
        }

        notificationManager.notify(
            NOTIFICATION_ID_UPDATED,
            Notification
                .Builder(service, NOTIFICATION_CHANNEL_ID_UPDATED)
                .setContentIntent(pendingSettingsIntent)
                .setContentTitle(service.getString(R.string.notification_updated_title))
                .setContentText(service.getString(R.string.notification_updated_text, channelText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_good_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showDownloadNotification(progress: Long, max: Long) {
        phase = Phase.DOWNLOAD
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_download_title, progress, max)
        )
    }

    fun showVerifyNotification(progress: Int) {
        phase = Phase.VERIFY
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_verify_title, progress.toLong(), 100)
        )
    }

    fun showInstallNotification(progress: Int) {
        phase = Phase.INSTALL
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_install_title, progress.toLong(), 100)
        )
    }

    fun showValidateNotification(progress: Int) {
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_validate_title, progress.toLong(), 100)
        )
    }

    fun showFinalizeNotification(progress: Int) {
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(R.string.notification_finalize_title, progress.toLong(), 100)
        )
    }

    fun cancelProgressNotification() {
        service.stopForeground(true)
    }

    fun showRebootNotification() {
        val reboot = PendingIntent.getBroadcast(
            service,
            PENDING_REBOOT_ID,
            Intent(service, RebootReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val rebootAction = Notification.Action
            .Builder(
                Icon.createWithResource(service.application, R.drawable.restart_alt_fill0_wght400_grad0_opsz48),
                service.getString(R.string.notification_reboot_action),
                reboot
            ).build()

        notificationManager.notify(
            NOTIFICATION_ID_REBOOT,
            Notification
                .Builder(service, NOTIFICATION_CHANNEL_ID_REBOOT)
                .addAction(rebootAction)
                .setContentIntent(pendingSettingsIntent)
                .setContentTitle(service.getString(R.string.notification_reboot_title))
                .setContentText(service.getString(R.string.notification_reboot_text))
                .setOngoing(true)
                .setShowWhen(true)
                .setTimeoutAfter(-1)
                .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    fun showFailureNotification(exceptionMessage: String?) {
        val (titleResId, contentResId) = when (phase) {
            Phase.CHECK -> Pair(
                R.string.notification_failed_check_title,
                R.string.notification_failed_check_text
            )
            Phase.DOWNLOAD -> Pair(
                R.string.notification_failed_download_title,
                R.string.notification_failed_download_text
            )
            Phase.VERIFY -> Pair(
                R.string.notification_failed_verify_title,
                R.string.notification_failed_verify_text
            )
            Phase.INSTALL -> Pair(
                R.string.notification_failed_install_title,
                R.string.notification_failed_install_text
            )
        }

        val text = "${service.getString(contentResId)}<br><br><tt>$exceptionMessage</tt>"
        val styledText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)

        notificationManager.notify(
            NOTIFICATION_ID_FAILURE,
            Notification
                .Builder(service, NOTIFICATION_CHANNEL_ID_FAILURE)
                .setContentIntent(pendingSettingsIntent)
                .setContentTitle(service.getString(titleResId))
                .setContentText(styledText)
                .setStyle(Notification.BigTextStyle().bigText(styledText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                .build()
        )
    }

    private fun buildProgressNotification(resId: Int, progress: Long, max: Long): Notification {
        val builder = Notification
            .Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
            .setContentIntent(pendingSettingsIntent)
            .setContentTitle(service.getString(resId))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.system_update_fill0_wght400_grad0_opsz48)

        if (max <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            val fraction = progress.toDouble() / max.toDouble()
            val maxScaled = 100
            val progressScaled = (fraction * maxScaled).toInt()
            builder.setProgress(maxScaled, progressScaled, false)
        }
        return builder.build()
    }

    private val pendingSettingsIntent: PendingIntent
        get() = PendingIntent.getActivity(
            service,
            PENDING_SETTINGS_ID,
            Intent(service, Settings::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1
        private const val NOTIFICATION_ID_REBOOT = 2
        private const val NOTIFICATION_ID_FAILURE = 3
        private const val NOTIFICATION_ID_UPDATED = 4
        private const val MAX_NOTIFICATION_ID_FOR_SERVICE = NOTIFICATION_ID_UPDATED
        private const val NOTIFICATION_ID_SET_SECURITY_PREVIEW = 1000
        private const val NOTIFICATION_CHANNEL_ID_PROGRESS = "progress"
        private const val NOTIFICATION_CHANNEL_ID_REBOOT = "updates2"
        private const val NOTIFICATION_CHANNEL_ID_FAILURE = "failure"
        private const val NOTIFICATION_CHANNEL_ID_UPDATED = "updated"
        private const val NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW = "set_security_preview"
        private const val PENDING_REBOOT_ID = 1
        private const val PENDING_SETTINGS_ID = 2
        private const val PENDING_SECURITY_PREVIEW_SETTINGS_ID = 3

        @JvmStatic
        fun createNotificationChannels(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val channels = mutableListOf<NotificationChannel>()

            // channels are unblockable by default with fixed notification permission
            val progress = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_PROGRESS,
                context.getString(R.string.notification_channel_progress),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setBlockable(true)
            }
            channels.add(progress)

            val reboot = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_REBOOT,
                context.getString(R.string.notification_channel_reboot),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            channels.add(reboot)

            val failure = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_FAILURE,
                context.getString(R.string.notification_channel_failure),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setBlockable(true)
            }
            channels.add(failure)

            val updated = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_UPDATED,
                context.getString(R.string.notification_channel_updated),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setBlockable(true)
            }
            channels.add(updated)

            val setSecurityPreview = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW,
                context.getString(R.string.notification_channel_set_security_preview),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
            }
            channels.add(setSecurityPreview)

            notificationManager.createNotificationChannels(channels)
        }

        @JvmStatic
        fun showSetSecurityPreviewNotification(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                NOTIFICATION_ID_SET_SECURITY_PREVIEW,
                Notification
                    .Builder(context, NOTIFICATION_CHANNEL_ID_SET_SECURITY_PREVIEW)
                    .setContentIntent(getPendingSecurityPreviewSettingsIntent(context))
                    .setContentTitle(context.getString(R.string.notification_set_security_preview_title))
                    .setContentText(context.getString(R.string.notification_set_security_preview_text))
                    .setOngoing(true)
                    .setShowWhen(true)
                    .setTimeoutAfter(-1)
                    .setSmallIcon(R.drawable.security_update_warning_fill0_wght400_grad0_opsz48)
                    .build()
            )
        }

        @JvmStatic
        fun cancelSetSecurityPreviewNotification(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID_SET_SECURITY_PREVIEW)
        }

        private fun getPendingSecurityPreviewSettingsIntent(context: Context): PendingIntent = PendingIntent
            .getActivity(
                context,
                PENDING_SECURITY_PREVIEW_SETTINGS_ID,
                Intent(context, SecurityPreviewSettings::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}
