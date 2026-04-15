/*
 * Copyright 2025 Google LLC
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

package app.sanctum.machina.core.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.sanctum.machina.core.data.KEY_MAIN_ACTIVITY_FQN
import app.sanctum.machina.core.data.KEY_MODEL_COMMIT_HASH
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_RATE
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import app.sanctum.machina.core.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import app.sanctum.machina.core.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import app.sanctum.machina.core.data.KEY_MODEL_EXTRA_DATA_URLS
import app.sanctum.machina.core.data.KEY_MODEL_IS_ZIP
import app.sanctum.machina.core.data.KEY_MODEL_NAME
import app.sanctum.machina.core.data.KEY_MODEL_START_UNZIPPING
import app.sanctum.machina.core.data.KEY_MODEL_TOTAL_BYTES
import app.sanctum.machina.core.data.KEY_MODEL_UNZIPPED_DIR
import app.sanctum.machina.core.data.KEY_MODEL_URL
import app.sanctum.machina.core.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }

          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            val connection = url.openConnection() as HttpURLConnection

            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )
            val outputFileBytes = outputTmpFile.length()
            if (outputFileBytes > 0) {
              connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
              connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()

            if (
              connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            ) {
              val contentRange = connection.getHeaderField("Content-Range")

              if (contentRange != null) {
                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                val byteRange = rangeParts[0].split("-")
                val startByte = byteRange[0].toLong()

                downloadedBytes += startByte
              }
            } else {
              throw IOException("HTTP error code: ${connection.responseCode}")
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputTmpFile, true)

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                setForeground(
                  createForegroundInfo(
                    progress = (downloadedBytes * 100 / totalBytes).toInt(),
                    modelName = modelName,
                  )
                )
                lastSetProgressTs = curTs
              }
            }

            outputStream.close()
            inputStream.close()

            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)

            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                if (!zipEntry.isDirectory) {
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                } else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        }
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    return createForegroundInfo(0)
  }

  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    // T6: resolve MainActivity FQN from work Data (injected by :app via DefaultDownloadRepository
    // companion) with a package-prefix guard. No hardcoded Gallery FQN in :core-runtime.
    val pendingIntent = buildMainActivityPendingIntent()

    val builder =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setProgress(100, progress, false)

    if (pendingIntent != null) {
      builder.setContentIntent(pendingIntent)
    }

    return ForegroundInfo(
      notificationId,
      builder.build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun buildMainActivityPendingIntent(): PendingIntent? {
    val fqn = inputData.getString(KEY_MAIN_ACTIVITY_FQN) ?: return null
    require(fqn.startsWith("app.sanctum.machina.")) { "Unexpected main activity FQN: $fqn" }
    val activityClass =
      try {
        Class.forName(fqn)
      } catch (e: ClassNotFoundException) {
        Log.e(TAG, "MainActivity class not found: $fqn", e)
        return null
      }
    val intent =
      Intent(applicationContext, activityClass).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    return PendingIntent.getActivity(
      applicationContext,
      0,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
