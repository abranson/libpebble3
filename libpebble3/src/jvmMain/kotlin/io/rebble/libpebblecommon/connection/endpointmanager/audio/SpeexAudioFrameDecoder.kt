package io.rebble.libpebblecommon.connection.endpointmanager.audio

import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import org.xiph.speex.SpeexDecoder
import java.io.StreamCorruptedException

actual class SpeexAudioFrameDecoder actual constructor() : AudioFrameDecoder {
    override fun open(encoderInfo: VoiceEncoderInfo): AudioPcmDecoder? {
        val info = encoderInfo as? VoiceEncoderInfo.Speex ?: return null
        val mode = when (info.sampleRate) {
            8000L -> 0
            16000L -> 1
            32000L -> 2
            else -> return null
        }
        if (info.frameSize != info.sampleRate.toInt() / 50) return null
        val decoder = SpeexDecoder()
        if (!decoder.init(mode, info.sampleRate.toInt(), 1, true)) return null
        return object : AudioPcmDecoder {
            override fun decode(frame: UByteArray): ByteArray? {
                if (frame.size <= 1) return null
                try {
                    // The watch prefixes each encoded frame with a quality byte.
                    decoder.processData(frame.asByteArray(), 1, frame.size - 1)
                } catch (e: StreamCorruptedException) {
                    return null
                } catch (e: IndexOutOfBoundsException) {
                    return null
                }
                return ByteArray(decoder.processedDataByteSize).also { decoder.getProcessedData(it, 0) }
            }

            override fun close() {}
        }
    }
}
