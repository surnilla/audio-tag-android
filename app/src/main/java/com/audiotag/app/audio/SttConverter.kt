package com.audiotag.app.audio

import android.util.Log
import com.audiotag.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class SttConverter {

    companion object {
        private const val TAG = "SttConverter"

        // Key is read from local.properties (groq.api.key) → BuildConfig at build time.
        // Never commit the key. Get a free key at https://console.groq.com
        private val GROQ_API_KEY: String get() = BuildConfig.GROQ_API_KEY

        private val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Transcribes mono 16-bit PCM via Groq Whisper API.
     * Converts to WAV, posts to Groq, returns transcript.
     */
    suspend fun recognize(pcmData: ByteArray, sampleRate: Int): String =
        withContext(Dispatchers.IO) {
            if (GROQ_API_KEY == "YOUR_GROQ_API_KEY_HERE") {
                throw IllegalStateException("Set GROQ_API_KEY in SttConverter.kt")
            }

            val wavData = buildWav(pcmData, sampleRate)
            Log.d(TAG, "Sending ${wavData.size} bytes WAV (${pcmData.size / 2 / sampleRate}s) to Groq Whisper")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart(
                    "file", "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $GROQ_API_KEY")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Groq response ${response.code}: $responseBody")

            if (!response.isSuccessful) {
                throw IllegalStateException("Groq API error ${response.code}: $responseBody")
            }

            JSONObject(responseBody).optString("text", "").trim()
        }

    /** Wraps mono 16-bit PCM in a minimal WAV/RIFF container. */
    private fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int32le(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            out.write(b)
        }
        fun int16le(v: Int) {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
            out.write(b)
        }

        out.write("RIFF".toByteArray())
        int32le(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int32le(16)
        int16le(1)              // PCM
        int16le(channels)
        int32le(sampleRate)
        int32le(byteRate)
        int16le(blockAlign)
        int16le(bitsPerSample)
        out.write("data".toByteArray())
        int32le(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }
}
