package app.sanctum.machina.core.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.sanctum.machina.core.settings.proto.AppSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object AppSettingsSerializer : Serializer<AppSettings> {
  override val defaultValue: AppSettings = AppSettings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): AppSettings {
    try {
      return AppSettings.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read AppSettings.", e)
    }
  }

  override suspend fun writeTo(t: AppSettings, output: OutputStream) {
    t.writeTo(output)
  }
}
