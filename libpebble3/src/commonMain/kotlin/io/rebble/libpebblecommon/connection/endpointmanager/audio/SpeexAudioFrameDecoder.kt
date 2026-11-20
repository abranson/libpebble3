package io.rebble.libpebblecommon.connection.endpointmanager.audio

import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer

expect class SpeexAudioFrameDecoder() : AudioFrameDecoder

/** Decodes a whole recording; throws if the codec is unsupported or any frame fails. */
@OptIn(ExperimentalUnsignedTypes::class)
suspend fun AudioFrameDecoder.decodeAll(
    encoderInfo: VoiceEncoderInfo,
    frames: Flow<UByteArray>,
): Buffer {
    val decoder = open(encoderInfo)
        ?: error("Unsupported voice encoding: ${encoderInfo::class.simpleName}")
    val output = Buffer()
    try {
        withContext(Dispatchers.IO) {
            frames.collect { frame ->
                output.write(decoder.decode(frame) ?: error("Failed to decode audio frame"))
            }
        }
    } finally {
        decoder.close()
    }
    return output
}
