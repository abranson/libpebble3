package io.rebble.libpebblecommon.linux.voice

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.rebble.libpebblecommon.linux.web.RebbleBootConfigProvider
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.flow.Flow

/**
 * Voice dictation for Rebble subscribers. Sailfish has no on-device speech engine, so audio goes to
 * Rebble's ASR ([RebbleAsrService]). The ASR endpoint host comes from the account's boot config
 * (`config.voice.languages[].endpoint`), which is only populated for entitled accounts — so an
 * empty list is how a non-subscriber presents. The ASR token is baked into that hostname; the POST
 * itself is unauthenticated (legacy Nuance).
 */
class RebbleTranscriptionProvider(
    private val bootConfig: RebbleBootConfigProvider,
) : TranscriptionProvider {
    private val logger = Logger.withTag("RebbleTranscription")

    // Explicit engine: HttpClient()'s ServiceLoader discovery is fragile under native image.
    private val asr = RebbleAsrService(HttpClient(CIO))

    override suspend fun canServeSession(): Boolean =
        bootConfig.get()?.config?.voice?.languages?.isNotEmpty() == true

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean,
    ): TranscriptionResult {
        if (encoderInfo !is VoiceEncoderInfo.Speex) {
            return TranscriptionResult.Error("Rebble ASR only supports Speex, got ${encoderInfo::class.simpleName}")
        }
        val voice = bootConfig.get()?.config?.voice
        if (voice == null || voice.languages.isEmpty()) {
            return TranscriptionResult.Error("Rebble voice config unavailable")
        }
        val language = RebbleAsrService.pickLanguage(voice, iso639_1Code = null)
            ?: return TranscriptionResult.Error("No Rebble language endpoint available")

        val host = extractHost(language.endpoint)
        logger.d { "Rebble ASR -> host=$host locale=${language.fourCharLocale}" }
        return asr.transcribe(
            endpointHost = host,
            encoderInfo = encoderInfo,
            fourCharLocale = language.fourCharLocale,
            audioFrames = audioFrames,
        )
    }

    private fun extractHost(endpoint: String): String =
        endpoint.removePrefix("https://").removePrefix("http://").substringBefore('/')
}
