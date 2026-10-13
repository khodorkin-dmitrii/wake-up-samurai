// SPDX-License-Identifier: LGPL-3.0-only
package com.yavin.wakeupsamurai.tts

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import com.yavin.wakeupsamurai.data.audio.AudioAssetStorage
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Small Android-native client compatible with the protocol used by rany2/edge-tts 7.2.8.
 * The consumer Edge endpoint is undocumented and may change without notice. Protocol details
 * were adapted from edge-tts constants.py, drm.py, and communicate.py (LGPL-3.0); see the
 * repository's THIRD_PARTY_NOTICES.md and third_party/edge-tts-LICENSE.txt.
 */
@Singleton
class EdgeSpeechGeneration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val validator: AudioFileValidator,
    private val assetRepository: AudioAssetRepository,
    private val assetStorage: AudioAssetStorage,
) : SpeechGeneration {
    override suspend fun generate(request: SpeechGenerationRequest): GeneratedSpeech =
        withContext(Dispatchers.IO) {
            val normalizedText = request.text.trim()
            require(normalizedText.isNotEmpty()) { "Text is required." }
            require(normalizedText.encodeToByteArray().size <= MAX_TEXT_BYTES) {
                "Text is too long for this feasibility spike."
            }

            val outputDirectory = File(context.filesDir, OUTPUT_DIRECTORY).apply { mkdirs() }
            val temporaryFile = File.createTempFile("edge-", ".mp3.part", outputDirectory)
            val startedAt = SystemClock.elapsedRealtime()

            try {
                receiveAudio(
                    text = normalizedText,
                    voice = request.voice,
                    destination = temporaryFile,
                )
                val duration = validator.validate(temporaryFile)
                val completedFile = File(
                    outputDirectory,
                    "speech-${request.revision}-${UUID.randomUUID()}.mp3",
                )
                promote(temporaryFile, completedFile)
                val assetId = UUID.randomUUID().toString()
                val registeredAsset = assetRepository.register(
                    AudioAsset(
                        id = assetId,
                        relativePath = assetStorage.relativePath(completedFile),
                        sourceText = normalizedText,
                        voice = request.voice,
                        languageTag = request.voice.substringBefore('-') + "-" +
                            request.voice.substringAfter('-').substringBefore('-'),
                        createdAt = Instant.now(),
                        durationMillis = duration,
                    ),
                )
                GeneratedSpeech(
                    assetId = registeredAsset.id,
                    file = completedFile,
                    durationMillis = duration,
                    sourceRevision = request.revision,
                    voice = request.voice,
                    generationLatencyMillis = SystemClock.elapsedRealtime() - startedAt,
                )
            } catch (exception: Throwable) {
                temporaryFile.delete()
                throw exception
            }
        }

    private suspend fun receiveAudio(text: String, voice: String, destination: File) {
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean(false)
            val output = FileOutputStream(destination)
            var receivedAudio = false

            fun fail(webSocket: WebSocket?, throwable: Throwable) {
                if (terminal.compareAndSet(false, true)) {
                    runCatching { output.close() }
                    webSocket?.cancel()
                    continuation.resumeWithException(
                        if (throwable is SpeechGenerationException) throwable
                        else SpeechGenerationException("The online speech request failed.", throwable),
                    )
                }
            }

            val request = Request.Builder()
                .url(webSocketUrl())
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", EDGE_EXTENSION_ORIGIN)
                .header("User-Agent", EDGE_USER_AGENT)
                .header("Cookie", "muid=${randomId().uppercase(Locale.US)};")
                .build()

            val webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val timestamp = edgeTimestamp()
                        val configuration =
                            "X-Timestamp:$timestamp\r\n" +
                                "Content-Type:application/json; charset=utf-8\r\n" +
                                "Path:speech.config\r\n\r\n" +
                                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                                "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                        val escaped = escapeXml(removeUnsupportedCharacters(text))
                        val ssml =
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                                "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                                escaped + "</prosody></voice></speak>"
                        val speech =
                            "X-RequestId:${randomId()}\r\n" +
                                "Content-Type:application/ssml+xml\r\n" +
                                "X-Timestamp:${timestamp}Z\r\n" +
                                "Path:ssml\r\n\r\n$ssml"
                        if (!webSocket.send(configuration) || !webSocket.send(speech)) {
                            fail(webSocket, SpeechGenerationException("The speech request could not be sent."))
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (headerValue(text.substringBefore("\r\n\r\n"), "Path") == "turn.end") {
                            if (!receivedAudio) {
                                fail(webSocket, SpeechGenerationException("The service returned no audio."))
                            } else if (terminal.compareAndSet(false, true)) {
                                output.close()
                                webSocket.close(1000, null)
                                continuation.resume(Unit)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            val payload = bytes.toByteArray()
                            if (payload.size < 2) {
                                throw SpeechGenerationException("The service returned malformed audio.")
                            }
                            val headerLength =
                                (payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)
                            if (headerLength + 2 > payload.size) {
                                throw SpeechGenerationException("The service returned malformed audio headers.")
                            }
                            val headers = payload.decodeToString(2, 2 + headerLength)
                            val audioStart = 2 + headerLength
                            val path = headerValue(headers, "Path")
                            val contentType = headerValue(headers, "Content-Type")
                            if (path != "audio") {
                                throw SpeechGenerationException("The service returned an unexpected response.")
                            }
                            if (contentType == null && audioStart >= payload.size) return
                            if (contentType != "audio/mpeg" || audioStart >= payload.size) {
                                throw SpeechGenerationException("The service returned invalid audio data.")
                            }
                            output.write(payload, audioStart, payload.size - audioStart)
                            receivedAudio = true
                        } catch (exception: Throwable) {
                            fail(webSocket, exception)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val status = response?.code?.let { " (HTTP $it)" }.orEmpty()
                        fail(webSocket, SpeechGenerationException("The online speech request failed$status.", t))
                    }
                },
            )

            continuation.invokeOnCancellation {
                if (terminal.compareAndSet(false, true)) {
                    runCatching { output.close() }
                    webSocket.cancel()
                    destination.delete()
                }
            }
        }
    }

    private fun promote(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = false)
            source.delete()
        }
        if (!destination.isFile) {
            throw SpeechGenerationException("The generated audio could not be stored.")
        }
    }

    private fun webSocketUrl(): String =
        "$WSS_URL&ConnectionId=${randomId()}&Sec-MS-GEC=${securityToken()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    private fun securityToken(): String {
        val seconds = System.currentTimeMillis() / 1_000L + WINDOWS_EPOCH_SECONDS
        val roundedSeconds = seconds - seconds % TOKEN_WINDOW_SECONDS
        val ticks = roundedSeconds * WINDOWS_TICKS_PER_SECOND
        return MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".encodeToByteArray())
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun edgeTimestamp(): String = SimpleDateFormat(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun headerValue(headers: String, name: String): String? = headers
        .lineSequence()
        .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
        .firstOrNull { it[0].equals(name, ignoreCase = true) }
        ?.get(1)
        ?.trim()

    private fun removeUnsupportedCharacters(text: String): String = buildString(text.length) {
        text.forEach { character ->
            append(if (character.code in 0..8 || character.code in 11..12 || character.code in 14..31) ' ' else character)
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val OUTPUT_DIRECTORY = "speech"
        const val MAX_TEXT_BYTES = 4_096
        const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
        const val WINDOWS_TICKS_PER_SECOND = 10_000_000L
        const val TOKEN_WINDOW_SECONDS = 300L
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
        const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        const val EDGE_EXTENSION_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
