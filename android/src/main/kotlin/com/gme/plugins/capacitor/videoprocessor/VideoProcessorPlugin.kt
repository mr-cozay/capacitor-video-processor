package com.gme.plugins.capacitor.videoprocessor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min

@CapacitorPlugin(name = "VideoProcessor")
class VideoProcessorPlugin : Plugin() {

    private val executor = Executors.newSingleThreadExecutor()

    @PluginMethod
    fun compressVideo(call: PluginCall) {
        val inputPath = call.getString("input")
        val outputPath = call.getString("output")

        if (inputPath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            call.reject("Les paramètres 'input' et 'output' sont obligatoires")
            return
        }

        val inPath = normalizeFilePath(inputPath)
        val outPath = normalizeFilePath(outputPath)

        executor.execute {
            try {
                processVideo(inPath, outPath)
                Handler(Looper.getMainLooper()).post {
                    call.resolve(JSObject().put("output", outputPath))
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    call.reject("Compression échouée : ${e.message}", e)
                }
            }
        }
    }

    private fun normalizeFilePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            return Uri.parse(trimmed).path ?: trimmed.removePrefix("file://")
        }
        return trimmed
    }

    /**
     * Sortie entre 480p et 720p (côté le plus court), en préservant le ratio.
     * Si le ratio ne permet pas d’atteindre 480p sans dépasser 1280px sur le grand côté, on reste au plus proche possible.
     */
    private fun computeTargetEncodeDimensions(decoderFormat: MediaFormat): Pair<Int, Int> {
        val rotation =
            if (decoderFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                decoderFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
        val w0 = decoderFormat.getInteger(MediaFormat.KEY_WIDTH).toFloat()
        val h0 = decoderFormat.getInteger(MediaFormat.KEY_HEIGHT).toFloat()
        if (w0 <= 0f || h0 <= 0f) {
            return Pair(1280, 720)
        }
        val (srcW, srcH) =
            if (rotation == 90 || rotation == 270) {
                Pair(h0, w0)
            } else {
                Pair(w0, h0)
            }
        val maxLong = 1280f
        val maxShort = 720f
        val minShort = 480f
        val srcLong = kotlin.math.max(srcW, srcH)
        val srcShort = kotlin.math.min(srcW, srcH)

        var scale = min(maxLong / srcLong, maxShort / srcShort)
        var outLong = srcLong * scale
        var outShort = srcShort * scale

        if (outShort < minShort) {
            val scaleUp = minShort / srcShort
            if (srcLong * scaleUp <= maxLong) {
                scale = scaleUp
                outLong = srcLong * scale
                outShort = srcShort * scale
            }
        }
        if (outLong > maxLong) {
            val c = maxLong / outLong
            outLong *= c
            outShort *= c
        }
        if (outShort > maxShort) {
            val c = maxShort / outShort
            outLong *= c
            outShort *= c
        }

        val landscape = srcW >= srcH
        val outW = if (landscape) outLong else outShort
        val outH = if (landscape) outShort else outLong
        val evenW = (outW.toInt() / 2) * 2
        val evenH = (outH.toInt() / 2) * 2
        return Pair(evenW.coerceAtLeast(2), evenH.coerceAtLeast(2))
    }

    private fun computeTargetBitrate(width: Int, height: Int): Int {
        val shorter = kotlin.math.min(width, height).toFloat()
        if (shorter <= 0f) {
            return 1_500_000
        }
        // ~1,4 Mbps à 480p, ~2,5 Mbps à 720p (aligné ordre de grandeur profil serveur Kwital).
        val t = ((shorter - 480f) / (720f - 480f)).coerceIn(0f, 1f)
        return (1_400_000 + t * 1_100_000).toInt()
    }

    private fun processVideo(inputPath: String, outputPath: String) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        var videoTrackIndex = -1
        var audioTrackIndex = -1

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && videoTrackIndex == -1 -> videoTrackIndex = i
                mime.startsWith("audio/") && audioTrackIndex == -1 -> audioTrackIndex = i
            }
        }

        if (videoTrackIndex == -1) {
            extractor.release()
            throw IllegalStateException("Aucune piste vidéo trouvée dans le fichier source")
        }

        extractor.selectTrack(videoTrackIndex)
        val decoderFormat = extractor.getTrackFormat(videoTrackIndex)
        val mime = decoderFormat.getString(MediaFormat.KEY_MIME)!!

        val (encW, encH) = computeTargetEncodeDimensions(decoderFormat)
        val bitRate = computeTargetBitrate(encW, encH)

        val encoderFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
            }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(decoderFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackMuxer = -1
        var audioTrackMuxer = -1
        var muxerStarted = false

        val decBufferInfo = MediaCodec.BufferInfo()
        val encBufferInfo = MediaCodec.BufferInfo()
        var decoderDone = false
        var encoderDone = false
        var encoderEosSent = false

        fun drainDecoderOutputs() {
            while (true) {
                val outDecoder = decoder.dequeueOutputBuffer(decBufferInfo, 0)
                when (outDecoder) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    else -> {
                        if (outDecoder < 0) {
                            break
                        }
                        val decoderEos =
                            (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val renderToSurface = decBufferInfo.size > 0 || decoderEos
                        decoder.releaseOutputBuffer(outDecoder, renderToSurface)
                        if (decoderEos && !encoderEosSent) {
                            encoder.signalEndOfInputStream()
                            encoderEosSent = true
                        }
                    }
                }
            }
        }

        fun drainEncoderOutputs() {
            while (!encoderDone) {
                val outEncoder = encoder.dequeueOutputBuffer(encBufferInfo, 0)
                when (outEncoder) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackMuxer = muxer.addTrack(encoder.outputFormat)
                            if (audioTrackIndex != -1) {
                                audioTrackMuxer =
                                    muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    else -> {
                        if (outEncoder < 0) {
                            break
                        }
                        if (muxerStarted) {
                            val encodedBuffer = encoder.getOutputBuffer(outEncoder)!!
                            if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                muxer.writeSampleData(videoTrackMuxer, encodedBuffer, encBufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outEncoder, false)
                        if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }
        }

        try {
            while (!encoderDone) {
                if (!decoderDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            decoderDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                drainDecoderOutputs()
                drainEncoderOutputs()

                if (encoderDone) {
                    break
                }

                if (!decoderDone) {
                    val outDecoder = decoder.dequeueOutputBuffer(decBufferInfo, 10_000)
                    when (outDecoder) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        else -> {
                            if (outDecoder >= 0) {
                                val decoderEos =
                                    (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) !=
                                        0
                                val renderToSurface =
                                    decBufferInfo.size > 0 || decoderEos
                                decoder.releaseOutputBuffer(outDecoder, renderToSurface)
                                if (decoderEos && !encoderEosSent) {
                                    encoder.signalEndOfInputStream()
                                    encoderEosSent = true
                                }
                            }
                        }
                    }
                } else {
                    val outEncoder = encoder.dequeueOutputBuffer(encBufferInfo, 10_000)
                    when (outEncoder) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrackMuxer = muxer.addTrack(encoder.outputFormat)
                                if (audioTrackIndex != -1) {
                                    audioTrackMuxer =
                                        muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
                                }
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        else -> {
                            if (outEncoder >= 0) {
                                if (muxerStarted) {
                                    val encodedBuffer = encoder.getOutputBuffer(outEncoder)!!
                                    if (encBufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG ==
                                        0
                                    ) {
                                        muxer.writeSampleData(
                                            videoTrackMuxer,
                                            encodedBuffer,
                                            encBufferInfo,
                                        )
                                    }
                                }
                                encoder.releaseOutputBuffer(outEncoder, false)
                                if (encBufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM !=
                                    0
                                ) {
                                    encoderDone = true
                                }
                            }
                        }
                    }
                }

                drainDecoderOutputs()
                drainEncoderOutputs()
            }

            if (audioTrackIndex != -1 && muxerStarted) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioBuffer = ByteBuffer.allocate(512 * 1024)
                val audioInfo = MediaCodec.BufferInfo()

                while (true) {
                    val size = extractor.readSampleData(audioBuffer, 0)
                    if (size < 0) break

                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(audioTrackMuxer, audioBuffer, audioInfo)
                    extractor.advance()
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            try {
                decoder.release()
            } catch (_: Exception) {
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            try {
                inputSurface.release()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            extractor.release()
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }
}
