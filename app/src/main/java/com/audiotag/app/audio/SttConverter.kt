package com.audiotag.app.audio

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SttConverter {

    companion object {
        private const val TAG = "SttConverter"

        // TODO: Replace with your Google Cloud Speech-to-Text API key.
        // Create one at https://console.cloud.google.com/ → APIs → Cloud Speech-to-Text.
        const val API_KEY = "YOUR_GOOGLE_CLOUD_STT_API_KEY"

        private const val STT_ENDPOINT =
            "https://speech.googleapis.com/v1/speech:recognize?key=$API_KEY"
    }

    /**
     * Sends 16 kHz mono 16-bit little-endian PCM to Google Cloud Speech-to-Text
     * and returns the transcript, or an empty string if no speech was detected.
     *
     * Must be called from a coroutine; does network I/O on [Dispatchers.IO].
     */
    suspend fun recognize(pcmData: ByteArray): String = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val requestBody = JSONObject().apply {
            put("config", JSONObject().apply {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", TtsConverter.TARGET_SAMPLE_RATE)
                put("languageCode", "en-US")
                put("model", "command_and_search")
            })
            put("audio", JSONObject().apply {
                put("content", encoded)
            })
        }.toString()

        Log.d(TAG, "Sending ${pcmData.size} PCM bytes to Cloud STT")

        val conn = URL(STT_ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody) }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                Log.e(TAG, "Cloud STT HTTP $responseCode: $errText")
                throw IllegalStateException("Cloud STT error $responseCode: $errText")
            }

            Log.d(TAG, "STT response: $responseText")

            val json = JSONObject(responseText)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return@withContext ""

            results.getJSONObject(0)
                .getJSONArray("alternatives")
                .getJSONObject(0)
                .getString("transcript")
        } finally {
            conn.disconnect()
        }
    }
}
