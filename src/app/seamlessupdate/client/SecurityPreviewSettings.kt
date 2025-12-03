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

import android.app.Activity
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.setupcompat.template.FooterBarMixin
import com.google.android.setupcompat.template.FooterButton
import com.google.android.setupcompat.util.WizardManagerHelper
import com.google.android.setupdesign.GlifLayout
import com.google.android.setupdesign.transition.TransitionHelper
import com.google.android.setupdesign.util.ThemeHelper

class SecurityPreviewSettings : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.GlifV4Theme_DayNight)
        ThemeHelper.trySetDynamicColor(this)

        super.onCreate(savedInstanceState)

        val isAnySetupWizard = WizardManagerHelper.isAnySetupWizard(intent)
        if (isAnySetupWizard) {
            TransitionHelper.applyForwardTransition(this)
            TransitionHelper.applyBackwardTransition(this)
        }

        val userManager = getSystemService(USER_SERVICE) as UserManager
        if (!userManager.isSystemUser) {
            throw SecurityException("system user only")
        }

        setContentView(R.layout.security_preview_settings_activity)
    }

    override fun onApplyThemeResource(theme: android.content.res.Resources.Theme, resid: Int, first: Boolean) {
        theme.applyStyle(R.style.SetupWizardPartnerResource, true)
        super.onApplyThemeResource(theme, resid, first)
    }

    class SettingsFragment : Fragment() {
        private var isUsingPreviewChannel = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.security_preview_settings_fragment, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val activity = requireActivity()

            val layout = view as GlifLayout

            layout.setIcon(activity.getDrawable(R.drawable.ic_updater_glif))
            layout.setHeaderText(R.string.security_preview_settings_title)
            layout.setDescriptionText(R.string.security_preview_settings_description)

            val footer = layout.getMixin(FooterBarMixin::class.java)
            val isAnySetupWizard = WizardManagerHelper.isAnySetupWizard(activity.intent)
            val buttonType = if (isAnySetupWizard) FooterButton.ButtonType.NEXT else FooterButton.ButtonType.DONE
            val buttonTextRes = if (isAnySetupWizard) R.string.next else R.string.save
            val primary = FooterButton
                .Builder(activity)
                .setText(getString(buttonTextRes))
                .setButtonType(buttonType)
                .setListener { onPrimaryAction() }
                .build()
            footer.primaryButton = primary

            isUsingPreviewChannel = if (savedInstanceState != null) {
                savedInstanceState.getBoolean(
                    Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL,
                    DEFAULT_SECURITY_PREVIEW_WHEN_UNSET
                )
            } else {
                shouldUseSecurityPreviewChannel(requireContext())
            }

            val container = requireNotNull(layout.findViewById<LinearLayout>(R.id.enabled_container))
            val checkbox = requireNotNull(layout.findViewById<CheckBox>(R.id.checkbox_enabled))

            updateUi(checkbox)
            container.setOnClickListener {
                isUsingPreviewChannel = !isUsingPreviewChannel
                updateUi(checkbox)
            }
            checkbox.setOnClickListener {
                isUsingPreviewChannel = !isUsingPreviewChannel
                updateUi(checkbox)
            }
        }

        private fun updateUi(checkbox: CheckBox) {
            checkbox.isChecked = isUsingPreviewChannel
        }

        private fun onPrimaryAction() {
            val activity = requireActivity()

            NotificationHandler.cancelSetSecurityPreviewNotification(activity)
            val prefs = Settings.getPreferences(activity)
            val res = prefs
                .edit()
                .putInt(Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL, if (isUsingPreviewChannel) 1 else 0)
                .commit()
            if (res) {
                if (!prefs.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                    PeriodicJob.schedule(requireContext())
                }
            } else {
                Toast.makeText(activity, R.string.security_preview_settings_error_saving, Toast.LENGTH_LONG).show()
                Log.e(
                    TAG,
                    "error saving ${Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL}, pref editor commit returned false"
                )
            }

            activity.setResult(Activity.RESULT_OK)
            activity.finish()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBoolean(Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL, isUsingPreviewChannel)
        }

        companion object {
            private const val TAG = "SecPreviewSettingsFrag"
            private const val DEFAULT_SECURITY_PREVIEW_WHEN_UNSET = true

            private fun shouldUseSecurityPreviewChannel(context: android.content.Context): Boolean = when (Settings
                .getPreferences(
                    context
                ).getInt(Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL, -1)) {
                0 -> false
                1 -> true
                else -> DEFAULT_SECURITY_PREVIEW_WHEN_UNSET
            }
        }
    }
}
