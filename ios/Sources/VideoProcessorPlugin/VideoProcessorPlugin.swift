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

        let writerVideoInput = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: 1280,
                AVVideoHeightKey: 720,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: 1_500_000,
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
