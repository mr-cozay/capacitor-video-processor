import AVFoundation
import Capacitor
import Foundation

/// Enregistrement Swift-only (SwiftPM / Capacitor 8) — pas de fichier `.m` requis.
@objc(VideoProcessorPlugin)
public class VideoProcessorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "VideoProcessorPlugin"
    public let jsName = "VideoProcessor"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "compressVideo", returnType: CAPPluginReturnPromise),
    ]

    /// Dimensions d’affichage (après `preferredTransform`).
    private func displaySize(for track: AVAssetTrack) -> CGSize {
        let size = track.naturalSize
        let t = track.preferredTransform
        let rect = CGRect(origin: .zero, size: size).applying(t)
        return CGSize(width: abs(rect.width), height: abs(rect.height))
    }

    /// Sortie entre 480p et 720p (côté court), ratio conservé.
    private func targetEncodeSize(display: CGSize) -> CGSize {
        let srcW = display.width
        let srcH = display.height
        guard srcW > 1, srcH > 1 else {
            return CGSize(width: 1280, height: 720)
        }
        let maxLong: CGFloat = 1280
        let maxShort: CGFloat = 720
        let minShort: CGFloat = 480
        let srcLong = max(srcW, srcH)
        let srcShort = min(srcW, srcH)

        var scale = min(maxLong / srcLong, maxShort / srcShort)
        var outLong = srcLong * scale
        var outShort = srcShort * scale

        if outShort < minShort {
            let scaleUp = minShort / srcShort
            if srcLong * scaleUp <= maxLong {
                scale = scaleUp
                outLong = srcLong * scale
                outShort = srcShort * scale
            }
        }
        if outLong > maxLong {
            let c = maxLong / outLong
            outLong *= c
            outShort *= c
        }
        if outShort > maxShort {
            let c = maxShort / outShort
            outLong *= c
            outShort *= c
        }

        let landscape = srcW >= srcH
        let outW = landscape ? outLong : outShort
        let outH = landscape ? outShort : outLong
        let ew = max(2, (Int(outW) / 2) * 2)
        let eh = max(2, (Int(outH) / 2) * 2)
        return CGSize(width: ew, height: eh)
    }

    private func targetBitrate(for size: CGSize) -> Int {
        let shorter = min(size.width, size.height)
        let t = max(0, min(1, (shorter - 480) / (720 - 480)))
        return Int(Double(1_400_000) + t * Double(1_100_000))
    }

    private func fileURL(from pathOrUrl: String) -> URL {
        let trimmed = pathOrUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed), url.isFileURL || url.scheme == "file" {
            return url
        }
        if trimmed.hasPrefix("file:"), let url = URL(string: trimmed) {
            return url
        }
        return URL(fileURLWithPath: (trimmed as NSString).expandingTildeInPath)
    }

    @objc func compressVideo(_ call: CAPPluginCall) {
        guard let inputStr = call.getString("input"),
            let outputStr = call.getString("output")
        else {
            call.reject("Les paramètres 'input' et 'output' sont obligatoires")
            return
        }

        let inputURL = fileURL(from: inputStr)
        let outputURL = fileURL(from: outputStr)

        try? FileManager.default.removeItem(at: outputURL)

        let asset = AVAsset(url: inputURL)

        guard let reader = try? AVAssetReader(asset: asset) else {
            call.reject("Impossible de créer l'AVAssetReader")
            return
        }

        guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .mp4) else {
            call.reject("Impossible de créer l'AVAssetWriter")
            return
        }

        guard let videoTrack = asset.tracks(withMediaType: .video).first else {
            call.reject("Aucune piste vidéo trouvée dans le fichier source")
            return
        }

        let readerVideoOutput = AVAssetReaderTrackOutput(
            track: videoTrack,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
        )
        readerVideoOutput.alwaysCopiesSampleData = false
        reader.add(readerVideoOutput)

        let transform = videoTrack.preferredTransform
        let display = displaySize(for: videoTrack)
        let targetSize = targetEncodeSize(display: display)
        let bitRate = targetBitrate(for: targetSize)

        let writerVideoInput = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: targetSize.width,
                AVVideoHeightKey: targetSize.height,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: bitRate,
                    AVVideoExpectedSourceFrameRateKey: 30,
                    AVVideoMaxKeyFrameIntervalKey: 60,
                    AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                ],
            ]
        )
        writerVideoInput.transform = transform
        writerVideoInput.expectsMediaDataInRealTime = false
        writer.add(writerVideoInput)

        let audioTrack = asset.tracks(withMediaType: .audio).first

        var readerAudioOutput: AVAssetReaderTrackOutput?
        var writerAudioInput: AVAssetWriterInput?

        if let audioTrack = audioTrack {
            let readerAudio = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: nil)
            readerAudio.alwaysCopiesSampleData = false
            reader.add(readerAudio)
            readerAudioOutput = readerAudio

            let writerAudio = AVAssetWriterInput(
                mediaType: .audio,
                outputSettings: [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVEncoderBitRateKey: 128_000,
                    AVNumberOfChannelsKey: 2,
                    AVSampleRateKey: 44_100,
                ]
            )
            writerAudio.expectsMediaDataInRealTime = false
            writer.add(writerAudio)
            writerAudioInput = writerAudio
        }

        reader.startReading()
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        let dispatchGroup = DispatchGroup()
        let queue = DispatchQueue(label: "com.gme.videoprocessor.mediaQueue")

        dispatchGroup.enter()
        var pumpVideo: (() -> Void)?
        pumpVideo = {
            writerVideoInput.requestMediaDataWhenReady(on: queue) {
                var finished = false
                while writerVideoInput.isReadyForMoreMediaData {
                    if let sample = readerVideoOutput.copyNextSampleBuffer() {
                        writerVideoInput.append(sample)
                    } else {
                        writerVideoInput.markAsFinished()
                        finished = true
                        dispatchGroup.leave()
                        break
                    }
                }
                if !finished {
                    pumpVideo?()
                }
            }
        }
        pumpVideo?()

        if let writerAudio = writerAudioInput, let readerAudio = readerAudioOutput {
            dispatchGroup.enter()
            var pumpAudio: (() -> Void)?
            pumpAudio = {
                writerAudio.requestMediaDataWhenReady(on: queue) {
                    var finished = false
                    while writerAudio.isReadyForMoreMediaData {
                        if let sample = readerAudio.copyNextSampleBuffer() {
                            writerAudio.append(sample)
                        } else {
                            writerAudio.markAsFinished()
                            finished = true
                            dispatchGroup.leave()
                            break
                        }
                    }
                    if !finished {
                        pumpAudio?()
                    }
                }
            }
            pumpAudio?()
        }

        dispatchGroup.notify(queue: queue) {
            if reader.status == .failed {
                writer.cancelWriting()
                call.reject("Lecture échouée : \(reader.error?.localizedDescription ?? "inconnu")")
                return
            }

            writer.finishWriting {
                if writer.status == .completed {
                    call.resolve(["output": outputStr])
                } else {
                    call.reject(
                        "Écriture échouée : \(writer.error?.localizedDescription ?? "inconnu")")
                }
            }
        }
    }
}
