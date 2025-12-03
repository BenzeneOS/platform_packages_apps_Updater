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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.MenuItem
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.preference.PreferenceFragment

class Settings : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userManager = getSystemService(USER_SERVICE) as UserManager
        if (!userManager.isSystemUser) {
            throw SecurityException("system user only")
        }

        setContentView(R.layout.settings_activity)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.setStorageDeviceProtected()
            setPreferencesFromResource(R.xml.settings, rootKey)

            requirePreference<Preference>(KEY_CHECK_FOR_UPDATES).onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    val context = requireContext()
                    if (!getPreferences(context).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                        val network = connectivityManager.activeNetwork
                        if (network == null) {
                            Log.w(TAG, "checkForUpdates.onClickListener – network will be unavailable")
                        }
                        val intent = Intent(context, Service::class.java)
                        intent.putExtra(Service.INTENT_EXTRA_IS_USER_INITIATED, true)
                        intent.putExtra(Service.INTENT_EXTRA_NETWORK, network)
                        context.startForegroundService(intent)
                    }
                    true
                }

            requirePreference<Preference>(KEY_NETWORK_TYPE).onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as String).toInt()
                    getPreferences(requireContext()).edit().putInt(KEY_NETWORK_TYPE, value).apply()
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext())
                    }
                    true
                }

            updateAndReturnSecurityPreviewPreference().onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    val context = requireContext()
                    val prefs = getPreferences(context)
                    val res = prefs
                        .edit()
                        .putInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, if (newValue as Boolean) 1 else 0)
                        .commit()
                    if (res) {
                        if (!prefs.getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                            PeriodicJob.schedule(requireContext())
                        }
                        NotificationHandler.cancelSetSecurityPreviewNotification(requireContext())
                    }
                    res
                }
        }

        private fun updateAndReturnSecurityPreviewPreference(): TwoStatePreference {
            val useSecurityPreviewChannel = requirePreference<TwoStatePreference>(KEY_USE_SECURITY_PREVIEW_CHANNEL)
            val newChecked = shouldUseSecurityPreviewChannel(requireContext())
            useSecurityPreviewChannel.isChecked = newChecked
            return useSecurityPreviewChannel
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                KEY_CHANNEL, KEY_BATTERY_NOT_LOW, KEY_REQUIRES_CHARGING -> {
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext())
                    }
                }
                KEY_IDLE_REBOOT -> {
                    if (!getIdleReboot(requireContext())) {
                        IdleReboot.cancel(requireContext())
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            val networkType = findPreference<ListPreference>(KEY_NETWORK_TYPE)
            networkType?.value = getNetworkType(requireContext()).toString()
            updateAndReturnSecurityPreviewPreference()
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        private fun <T : Preference> requirePreference(key: String): T = requireNotNull(findPreference(key))

        companion object {
            private const val TAG = "SettingsFragment"
        }
    }

    companion object {
        private const val KEY_CHANNEL = "channel"
        const val KEY_USE_SECURITY_PREVIEW_CHANNEL = "use_security_preview_channel"
        private const val KEY_NETWORK_TYPE = "network_type"
        private const val KEY_BATTERY_NOT_LOW = "battery_not_low"
        private const val KEY_REQUIRES_CHARGING = "requires_charging"
        private const val KEY_IDLE_REBOOT = "idle_reboot"
        private const val KEY_CHECK_FOR_UPDATES = "check_for_updates"
        const val KEY_WAITING_FOR_REBOOT = "waiting_for_reboot"

        @JvmStatic
        fun getPreferences(context: Context): SharedPreferences {
            val deviceContext = context.createDeviceProtectedStorageContext()
            return PreferenceManager.getDefaultSharedPreferences(deviceContext)
        }

        @JvmStatic
        fun getChannel(context: Context): String {
            val base = getPreferences(context).getString(
                KEY_CHANNEL,
                context.getString(R.string.channel_default)
            )!!
            return if (shouldUseSecurityPreviewChannel(context)) {
                "$base-security-preview"
            } else {
                base
            }
        }

        @JvmStatic
        fun shouldUseSecurityPreviewChannel(context: Context): Boolean = when (getPreferences(
            context
        ).getInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, -1)) {
            0 -> false
            1 -> true
            else -> false
        }

        @JvmStatic
        fun getNetworkType(context: Context): Int = getPreferences(context).getInt(
            KEY_NETWORK_TYPE,
            context.getString(R.string.network_type_default).toInt()
        )

        @JvmStatic
        fun getBatteryNotLow(context: Context): Boolean = getPreferences(context).getBoolean(
            KEY_BATTERY_NOT_LOW,
            context.getString(R.string.battery_not_low_default).toBoolean()
        )

        @JvmStatic
        fun getRequiresCharging(context: Context): Boolean = getPreferences(context).getBoolean(
            KEY_REQUIRES_CHARGING,
            context.getString(R.string.requires_charging_default).toBoolean()
        )

        @JvmStatic
        fun getIdleReboot(context: Context): Boolean = getPreferences(context).getBoolean(
            KEY_IDLE_REBOOT,
            context.getString(R.string.idle_reboot_default).toBoolean()
        )
    }
}
