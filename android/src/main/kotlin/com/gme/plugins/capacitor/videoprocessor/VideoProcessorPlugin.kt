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

        val encoderFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
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

        extractor.selectTrack(videoTrackIndex)
        val decoderFormat = extractor.getTrackFormat(videoTrackIndex)
        val mime = decoderFormat.getString(MediaFormat.KEY_MIME)!!

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(decoderFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackMuxer = -1
        var audioTrackMuxer = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var decoderDone = false
        var encoderDone = false
        var encoderEosSent = false

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

                val outDecoder = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outDecoder == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outDecoder == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    outDecoder >= 0 -> {
                        val decoderEos =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val render = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outDecoder, render)
                        if (decoderEos && !encoderEosSent) {
                            encoder.signalEndOfInputStream()
                            encoderEosSent = true
                        }
                    }
                }

                val outEncoder = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outEncoder == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outEncoder == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
                    outEncoder >= 0 -> {
                        if (muxerStarted) {
                            val encodedBuffer = encoder.getOutputBuffer(outEncoder)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                muxer.writeSampleData(videoTrackMuxer, encodedBuffer, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outEncoder, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            if (audioTrackIndex != -1 && muxerStarted) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)

                val audioBuffer = ByteBuffer.allocate(64 * 1024)
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
            decoder.release()
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            inputSurface.release()
            extractor.release()
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }
}
