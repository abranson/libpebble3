package io.rebble.libpebblecommon.connection.endpointmanager.audio

import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo

@OptIn(ExperimentalUnsignedTypes::class)
actual class SpeexAudioFrameDecoder actual constructor() : AudioFrameDecoder {
    override fun open(encoderInfo: VoiceEncoderInfo): AudioPcmDecoder? {
        val speex = encoderInfo as? VoiceEncoderInfo.Speex ?: return null
        val codec = SpeexCodec(
            sampleRate = speex.sampleRate,
            bitRate = speex.bitRate,
            frameSize = speex.frameSize,
        )
        val pcm = ByteArray(speex.frameSize * Short.SIZE_BYTES)
        return object : AudioPcmDecoder {
            override fun decode(frame: UByteArray): ByteArray? {
                val result = codec.decodeFrame(frame.asByteArray(), pcm, hasHeaderByte = true)
                return if (result == SpeexDecodeResult.Success) pcm.copyOf() else null
            }

            override fun close() {
                codec.close()
            }
        }
    }
}
