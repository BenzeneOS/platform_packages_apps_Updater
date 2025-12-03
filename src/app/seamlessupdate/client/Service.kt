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

import android.app.IntentService
import android.content.Intent
import android.net.Network
import android.os.Build.DEVICE
import android.os.Build.FINGERPRINT
import android.os.Build.VERSION.INCREMENTAL
import android.os.PowerManager
import android.os.RecoverySystem
import android.os.ServiceSpecificException
import android.os.SystemProperties
import android.os.UpdateEngine
import android.os.UpdateEngine.ErrorCodeConstants
import android.os.UpdateEngine.UpdateStatusConstants
import android.os.UpdateEngineCallback
import android.os.storage.StorageManager
import android.util.Log
import libcore.io.IoUtils
import org.benzeneos.tls.ModernTLSSocketFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.URL
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

class Service : IntentService(TAG) {
    private val tlsSocketFactory = ModernTLSSocketFactory()
    private lateinit var notificationHandler: NotificationHandler
    private var mUpdating = false

    override fun onCreate() {
        super.onCreate()
        notificationHandler = NotificationHandler(this)
    }

    private fun fetchData(network: Network, path: String): HttpsURLConnection {
        val url = URL(getString(R.string.url) + path)
        val urlConnection = network.openConnection(url) as HttpsURLConnection
        urlConnection.sslSocketFactory = tlsSocketFactory
        urlConnection.connectTimeout = CONNECT_TIMEOUT
        urlConnection.readTimeout = READ_TIMEOUT
        return urlConnection
    }

    private fun applyUpdate(
        streaming: Boolean,
        payloadOffset: Long,
        headerKeyValuePairs: Array<String>,
        incremental: Boolean
    ) {
        notificationHandler.showInstallNotification(0)

        val monitor = CountDownLatch(1)
        val engine = UpdateEngine()
        val callback = object : UpdateEngineCallback() {
            var errorCode: Int? = null

            override fun onStatusUpdate(status: Int, percent: Float) {
                Log.d(TAG, "onStatusUpdate: $status, ${percent * 100}%")
                when (status) {
                    UpdateStatusConstants.DOWNLOADING ->
                        notificationHandler.showInstallNotification((percent * 100).toInt())
                    UpdateStatusConstants.VERIFYING ->
                        notificationHandler.showValidateNotification((percent * 100).toInt())
                    UpdateStatusConstants.FINALIZING ->
                        notificationHandler.showFinalizeNotification((percent * 100).toInt())
                }
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                Log.d(TAG, "onPayloadApplicationComplete: $errorCode")
                this.errorCode = errorCode
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    UPDATE_PATH.delete()
                    annoyUser()
                } else {
                    UPDATE_PATH.delete()
                    if (incremental && errorCode == UPDATE_ENGINE_DOWNLOAD_STATE_INITIALIZATION_ERROR) {
                        val preferences = Settings.getPreferences(this@Service)
                        val downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null)
                        preferences.edit().putString(PREFERENCE_FAILED_INCREMENTAL, downloadFile).commit()
                    }
                }
                monitor.countDown()
            }
        }
        engine.bind(callback)
        if (streaming) {
            val preferences = Settings.getPreferences(this)
            val downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE.replace("-streaming", ""), null)
            engine.applyPayload(getString(R.string.url) + downloadFile, payloadOffset, 0, headerKeyValuePairs)
        } else {
            UPDATE_PATH.setReadable(true, false)
            engine.applyPayload("file://$UPDATE_PATH", payloadOffset, 0, headerKeyValuePairs)
        }
        try {
            monitor.await()
        } catch (_: InterruptedException) {
        }

        if (!engine.unbind()) {
            Log.e(TAG, "unable to unbind update_engine")
        }

        if (callback.errorCode != null && callback.errorCode != ErrorCodeConstants.SUCCESS) {
            throw IOException("update_engine error code: ${callback.errorCode}")
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun onDownloadFinished(
        streaming: Boolean,
        targetBuildDate: Long,
        targetIncremental: String,
        channel: String
    ) {
        try {
            notificationHandler.showVerifyNotification(0)
            RecoverySystem.verifyPackage(UPDATE_PATH, { progress ->
                Log.d(TAG, "verifyPackage: $progress%")
                notificationHandler.showVerifyNotification(progress)
            }, null)
        } catch (e: GeneralSecurityException) {
            UPDATE_PATH.delete()
            throw e
        }

        try {
            ZipFile(UPDATE_PATH).use { zipFile ->
                val metadata = getEntry(zipFile, "META-INF/com/android/metadata")
                var timestamp: Long = 0
                var incremental: String? = null
                var device: String? = null
                var serialno: String? = null
                var type: String? = null
                var streamingPropertyFiles: Array<String>? = null
                var sourceIncremental: String? = null
                var sourceFingerprint: String? = null

                BufferedReader(InputStreamReader(zipFile.getInputStream(metadata))).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val pair = line!!.split("=", limit = 2)
                        if (pair.size < 2) continue
                        when (pair[0]) {
                            "post-timestamp" -> timestamp = pair[1].toLong()
                            "post-build-incremental" -> incremental = pair[1]
                            "pre-device" -> device = pair[1]
                            "serialno" -> serialno = pair[1]
                            "ota-type" -> type = pair[1]
                            "ota-streaming-property-files" -> streamingPropertyFiles =
                                pair[1].trim().split(",").toTypedArray()
                            "pre-build-incremental" -> sourceIncremental = pair[1]
                            "pre-build" -> sourceFingerprint = pair[1]
                        }
                    }
                }

                if (timestamp != targetBuildDate) {
                    throw GeneralSecurityException("timestamp does not match server metadata")
                }
                if (targetIncremental != incremental) {
                    throw GeneralSecurityException("incremental does not match server metadata")
                }
                if (DEVICE != device) {
                    throw GeneralSecurityException("device mismatch")
                }
                if (serialno != null) {
                    throw GeneralSecurityException("serialno constraint not permitted")
                }
                if (type != "AB") {
                    throw GeneralSecurityException("package is not an A/B update")
                }
                if (sourceIncremental != null && sourceIncremental != INCREMENTAL) {
                    throw GeneralSecurityException("source incremental mismatch")
                }
                if (sourceFingerprint != null && sourceFingerprint != FINGERPRINT) {
                    throw GeneralSecurityException("source fingerprint mismatch")
                }

                var payloadOffset: Long? = null
                for (streamingPropertyFile in streamingPropertyFiles!!) {
                    val properties = streamingPropertyFile.split(":")
                    if (properties[0] == "payload.bin") {
                        payloadOffset = properties[1].toLong()
                        break
                    }
                }
                if (payloadOffset == null) {
                    throw GeneralSecurityException("payload offset missing")
                }

                Files.deleteIfExists(CARE_MAP_PATH.toPath())
                val careMapEntry = zipFile.getEntry("care_map.pb")
                if (careMapEntry == null) {
                    Log.w(TAG, "care_map.pb missing")
                } else {
                    Files.copy(zipFile.getInputStream(careMapEntry), CARE_MAP_PATH.toPath())
                    CARE_MAP_PATH.setReadable(true, false)
                }

                val payloadProperties = getEntry(zipFile, "payload_properties.txt")
                val headerKeyValuePairs: Array<String>
                BufferedReader(InputStreamReader(zipFile.getInputStream(payloadProperties))).use { propertiesReader ->
                    headerKeyValuePairs = propertiesReader.lines().toArray { size -> arrayOfNulls<String>(size) }
                }
                applyUpdate(streaming, payloadOffset, headerKeyValuePairs, sourceIncremental != null)
            }
        } catch (e: GeneralSecurityException) {
            UPDATE_PATH.delete()
            throw e
        }
    }

    private fun annoyUser() {
        PeriodicJob.cancel(this)
        val preferences = Settings.getPreferences(this)
        preferences.edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, true).apply()
        if (Settings.getIdleReboot(this)) {
            IdleReboot.schedule(this)
        }
        notificationHandler.showRebootNotification()
    }

    override fun onHandleIntent(intent: Intent?) {
        Log.d(TAG, "onHandleIntent")

        if (intent == null) return

        val network = intent.getParcelableExtra(INTENT_EXTRA_NETWORK, Network::class.java)
        val serviceIsUserInitiated = intent.getBooleanExtra(INTENT_EXTRA_IS_USER_INITIATED, false)
        if (serviceIsUserInitiated) Log.d(TAG, "onHandleIntent() – service is user-initiated")

        val pm = getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:$TAG")
        var connection: HttpsURLConnection? = null
        var input: InputStream? = null
        try {
            wakeLock.acquire()

            if (mUpdating) {
                Log.d(TAG, "updating already, returning early")
                return
            }
            val preferences = Settings.getPreferences(this)
            if (preferences.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                Log.d(TAG, "updated already, waiting for reboot")
                return
            }
            mUpdating = true
            notificationHandler.start()

            if (network == null) {
                throw IOException("Network is unavailable")
            }

            val channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this))

            Log.d(TAG, "fetching metadata for $DEVICE-$channel")
            connection = fetchData(network, "$DEVICE-$channel")
            val metadata: Array<String>
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                metadata = reader.readLine().split(" ").toTypedArray()
            }

            val targetIncremental = metadata[0]
            val targetBuildDate = metadata[1].toLong()
            val sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0)
            if (targetBuildDate <= sourceBuildDate) {
                notificationHandler.showUpdatedNotification(channel)
                Log.d(TAG, "targetBuildDate: $targetBuildDate not higher than sourceBuildDate: $sourceBuildDate")
                mUpdating = false
                return
            }
            val targetDevice = metadata[2]
            if (targetDevice != DEVICE) {
                throw GeneralSecurityException("targetDevice: $targetDevice does not match device: $DEVICE")
            }
            val targetChannel = metadata[3]
            if (targetChannel != channel) {
                throw GeneralSecurityException("targetChannel: $targetChannel does not match channel: $channel")
            }

            notificationHandler.showDownloadNotification(0, 100)

            var downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null)
            var downloaded: Long
            var contentLength: Long

            val streaming = SystemProperties.getBoolean("sys.update.streaming_test", false)

            val streamingPrefix = if (streaming) "-streaming" else ""
            val incrementalUpdate = "$DEVICE$streamingPrefix-incremental-$INCREMENTAL-$targetIncremental.zip"
            val fullUpdate = "$DEVICE$streamingPrefix-ota_update-$targetIncremental.zip"

            downloaded = if (incrementalUpdate == downloadFile || fullUpdate == downloadFile) {
                UPDATE_PATH.length()
            } else {
                0
            }

            if (downloaded > 0) {
                Log.d(TAG, "resume fetch of $downloadFile from $downloaded bytes")
                connection = fetchData(network, downloadFile!!)
                connection.setRequestProperty("Range", "bytes=$downloaded-")
                val responseCode = connection.responseCode
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously")
                    onDownloadFinished(streaming, targetBuildDate, targetIncremental, channel)
                    return
                }
                if (responseCode == HTTP_NOT_FOUND && incrementalUpdate == downloadFile) {
                    connection.errorStream?.close()
                    downloaded = 0
                    UPDATE_PATH.delete()

                    Log.d(TAG, "previous incremental not found, fetch full update $fullUpdate")
                    downloadFile = fullUpdate
                    connection = fetchData(network, downloadFile)
                }
                contentLength = connection.contentLengthLong + downloaded
                input = connection.inputStream
            } else {
                Files.deleteIfExists(UPDATE_PATH.toPath())

                val failedIncremental = preferences.getString(PREFERENCE_FAILED_INCREMENTAL, null)
                if (incrementalUpdate == failedIncremental) {
                    Log.d(TAG, "incremental update initialization failed, fetch full update $fullUpdate")
                    downloadFile = fullUpdate
                    connection = fetchData(network, downloadFile)
                } else {
                    Log.d(TAG, "fetch incremental $incrementalUpdate")
                    downloadFile = incrementalUpdate
                    connection = fetchData(network, downloadFile)
                    if (connection.responseCode == HTTP_NOT_FOUND) {
                        connection.errorStream?.close()

                        Log.d(TAG, "incremental not found, fetch full update $fullUpdate")
                        downloadFile = fullUpdate
                        connection = fetchData(network, downloadFile)
                    }
                }
                contentLength = connection.contentLengthLong
                input = connection.inputStream
            }

            notificationHandler.showDownloadNotification(downloaded, contentLength)

            val requiredBytes = contentLength - downloaded
            try {
                val sm = getSystemService(StorageManager::class.java)
                sm.allocateBytes(sm.getUuidForPath(UPDATE_PATH), requiredBytes, StorageManager.FLAG_ALLOCATE_AGGRESSIVE)
            } catch (e: IOException) {
                Log.d(TAG, "unable to allocate $requiredBytes bytes, proceeding anyway", e)
            }

            FileOutputStream(UPDATE_PATH, downloaded != 0L).use { output ->
                preferences.edit().putString(PREFERENCE_DOWNLOAD_FILE, downloadFile).commit()

                var bytesRead: Int
                var last = System.nanoTime()
                val buffer = ByteArray(16384)
                while (input!!.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    val now = System.nanoTime()
                    if (now - last > 1_000_000_000) {
                        Log.d(TAG, "downloaded $downloaded from $contentLength bytes")
                        notificationHandler.showDownloadNotification(downloaded, contentLength)
                        last = now
                    }
                }
            }

            Log.d(TAG, "download completed")
            onDownloadFinished(streaming, targetBuildDate, targetIncremental, channel)
        } catch (e: Exception) {
            when (e) {
                is GeneralSecurityException, is IOException, is ServiceSpecificException -> {
                    Log.e(TAG, "failed to download and install update", e)
                    notificationHandler.showFailureNotification(e.message)
                    mUpdating = false
                    if (serviceIsUserInitiated) {
                        Log.w(
                            TAG,
                            "onHandleIntent() – service failed but failure is ignored because it was user-initiated"
                        )
                    } else {
                        PeriodicJob.scheduleRetry(this)
                        Log.w(TAG, "onHandleIntent() – service failed but has been scheduled for retry")
                    }
                }
                else -> throw e
            }
        } finally {
            IoUtils.closeQuietly(input)
            connection?.disconnect()
            notificationHandler.cancelProgressNotification()
            Log.d(TAG, "release wake lock")
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "Service"
        const val INTENT_EXTRA_NETWORK = "network"
        const val INTENT_EXTRA_IS_USER_INITIATED = "is_user_initiated"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private val CARE_MAP_PATH = File("/data/ota_package/care_map.pb")
        private val UPDATE_PATH = File("/data/ota_package/update.zip")
        private const val PREFERENCE_DOWNLOAD_FILE = "download_file"
        private const val PREFERENCE_FAILED_INCREMENTAL = "failed_incremental"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val UPDATE_ENGINE_DOWNLOAD_STATE_INITIALIZATION_ERROR = 20

        @Throws(GeneralSecurityException::class)
        private fun getEntry(zipFile: ZipFile, name: String): ZipEntry = zipFile.getEntry(name)
            ?: throw GeneralSecurityException("missing zip entry: $name")
    }
}
