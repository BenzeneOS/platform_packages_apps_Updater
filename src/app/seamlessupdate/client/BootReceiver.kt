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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.getSystemService(UserManager::class.java).isSystemUser) {
            val preferences = Settings.getPreferences(context)
            preferences.edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, false).apply()
            PeriodicJob.schedule(context)

            if (!preferences.contains(Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL)) {
                NotificationHandler.showSetSecurityPreviewNotification(context)
            }
        } else {
            context.packageManager.setApplicationEnabledSetting(
                context.packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0
            )
        }
    }
}
