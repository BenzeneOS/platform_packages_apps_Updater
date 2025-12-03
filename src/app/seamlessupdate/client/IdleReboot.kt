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

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

class IdleReboot : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        RebootReceiver.reboot(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "IdleReboot"
        private const val JOB_ID_IDLE_REBOOT = 3
        private const val MIN_LATENCY_MILLIS = 5 * 60 * 1000L

        @JvmStatic
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val serviceName = ComponentName(context, IdleReboot::class.java)
            val result = scheduler.schedule(
                JobInfo
                    .Builder(JOB_ID_IDLE_REBOOT, serviceName)
                    .setRequiresDeviceIdle(true)
                    .setMinimumLatency(MIN_LATENCY_MILLIS)
                    .build()
            )
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "Job schedule failed")
            }
        }

        @JvmStatic
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID_IDLE_REBOOT)
        }
    }
}
