import Flutter
import UIKit
import AVFoundation

public class SwiftFlutterAudioRecorderPlugin: NSObject, FlutterPlugin, AVAudioRecorderDelegate {
    // status - unset, initialized, recording, paused, stopped
    var status = "unset"
    var hasPermissions = false
    var mExtension = ""
    var mPath = ""
    var mSampleRate = 16000
    var channel = 0
    var startTime: Date!
    var settings: [String:Int]!
    var audioRecorder: AVAudioRecorder!
    
    let audioStreamHandler: AudioStreamHandler!
    var eventChannel: FlutterEventChannel!
    
    public override init() {
      self.audioStreamHandler = AudioStreamHandler()
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = SwiftFlutterAudioRecorderPlugin()
        let methodChannel = FlutterMethodChannel(name: "flutter_audio_recorder/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methodChannel)

        instance.eventChannel = FlutterEventChannel(name: "flutter_audio_recorder/events", binaryMessenger: registrar.messenger())
        instance.eventChannel.setStreamHandler(instance.audioStreamHandler)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "current":
            print("current")
            
            if audioRecorder == nil {
                result(nil)
            } else {
                let dic = call.arguments as! [String : Any]
                channel = dic["channel"] as? Int ?? 0
                
                audioRecorder.updateMeters()
                let duration = Int(audioRecorder.currentTime * 1000)
                var recordingResult = [String : Any]()
                recordingResult["duration"] = duration
                recordingResult["path"] = mPath
                recordingResult["audioFormat"] = mExtension
                recordingResult["peakPower"] = audioRecorder.peakPower(forChannel: channel)
                recordingResult["averagePower"] = audioRecorder.averagePower(forChannel: channel)
                recordingResult["isMeteringEnabled"] = audioRecorder.isMeteringEnabled
                recordingResult["status"] = status
                result(recordingResult)
            }
        case "init":
            print("init")
            
            let dic = call.arguments as! [String : Any]
            mExtension = dic["extension"] as? String ?? ""
            mPath = dic["path"] as? String ?? ""
            mSampleRate = dic["sampleRate"] as? Int ?? 16000
            print("m:", mExtension, mPath)
            startTime = Date()
            if mPath == "" {
                let documentsPath = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true)[0]
                mPath = documentsPath + "/" + String(Int(startTime.timeIntervalSince1970)) + ".m4a"
                print("path: " + mPath)
            }
            
            settings = [
                AVFormatIDKey: getOutputFormatFromString(mExtension),
                AVSampleRateKey: mSampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]
            
            do {
                #if swift(>=4.2)
                try AVAudioSession.sharedInstance().setCategory(AVAudioSession.Category.playAndRecord, options: AVAudioSession.CategoryOptions.defaultToSpeaker)
                #else
                try AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayAndRecord, with: AVAudioSessionCategoryOptions.defaultToSpeaker)
                #endif
                try AVAudioSession.sharedInstance().setActive(true)
                audioRecorder = try AVAudioRecorder(url: URL(string: mPath)!, settings: settings)
                audioRecorder.delegate = self
                audioRecorder.isMeteringEnabled = true
                audioRecorder.prepareToRecord()
                let duration = Int(audioRecorder.currentTime * 1000)
                status = "initialized"
                var recordingResult = [String : Any]()
                recordingResult["duration"] = duration
                recordingResult["path"] = mPath
                recordingResult["audioFormat"] = mExtension
                recordingResult["peakPower"] = 0
                recordingResult["averagePower"] = 0
                recordingResult["isMeteringEnabled"] = audioRecorder.isMeteringEnabled
                recordingResult["status"] = status
                
                NotificationCenter.default.addObserver(self,
                selector: #selector(handleInterruption),
                name: .AVAudioSessionInterruption,
                object: nil)
                
                result(recordingResult)
            } catch {
                print("fail")
                result(FlutterError(code: "", message: "Failed to init", details: error))
            }
        case "start":
            print("start")
            
            if status == "initialized" {
                audioRecorder.record()
                status = "recording"
            }
            
            result(nil)
            
        case "stop":
            print("stop")
            // Remove observer
            if audioRecorder == nil || status == "unset" {
                result(nil)
            } else {
                audioRecorder.updateMeters()

                let duration = Int(audioRecorder.currentTime * 1000)
                status = "stopped"
                var recordingResult = [String : Any]()
                recordingResult["duration"] = duration
                recordingResult["path"] = mPath
                recordingResult["audioFormat"] = mExtension
                recordingResult["peakPower"] = audioRecorder.peakPower(forChannel: channel)
                recordingResult["averagePower"] = audioRecorder.averagePower(forChannel: channel)
                recordingResult["isMeteringEnabled"] = audioRecorder.isMeteringEnabled
                recordingResult["status"] = status

                audioRecorder.stop()
                audioRecorder = nil
                
                // Remove interruption listener
                NotificationCenter.default.removeObserver(self, name: .AVAudioSessionInterruption, object: nil)
                
                result(recordingResult)
            }
        case "pause":
            print("pause")
            
            if audioRecorder == nil {
                result(nil)
            }
            
            if status == "recording" {
                audioRecorder.pause()
                status = "paused"
            }
            
            result(nil)
        case "resume":
            print("resume")
        
            if audioRecorder == nil {
                result(nil)
            }
            
            if status == "paused" {
                audioRecorder.record()
                status = "recording"
            }
            
            result(nil)
        case "combineFiles":
            let dic = call.arguments as! [String : Any]
            let files: [String] = dic["files"] as? [String] ?? []
            let fileUrls : [URL] = files.map({ URL(fileURLWithPath: $0) })
            if let outputUrlPath = dic["outputPath"] as? String {
                let outputUrl = URL(fileURLWithPath: outputUrlPath)
                mergeAudioFiles(outputUrl: outputUrl, audioFileUrls: fileUrls) { (masterFileUrl, error) in
                    result(masterFileUrl?.path ?? nil)
                }
            }
            break
            
        case "hasPermissions":
            print("hasPermissions")
            var permission: AVAudioSession.RecordPermission
            #if swift(>=4.2)
            permission = AVAudioSession.sharedInstance().recordPermission
            #else
            permission = AVAudioSession.sharedInstance().recordPermission()
            #endif
            
            switch permission {
            case .granted:
                print("granted")
                hasPermissions = true
                result(hasPermissions)
                break
            case .denied:
                print("denied")
                hasPermissions = false
                result(hasPermissions)
                break
            case .undetermined:
                print("undetermined")

                AVAudioSession.sharedInstance().requestRecordPermission() { [unowned self] allowed in
                    DispatchQueue.main.async {
                        if allowed {
                            self.hasPermissions = true
                            print("undetermined true")
                            result(self.hasPermissions)
                        } else {
                            self.hasPermissions = false
                            print("undetermined false")
                            result(self.hasPermissions)
                        }
                    }
                }
                break
            default:
                result(hasPermissions)
                break
            }
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    @objc func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
                return
        }
        
        // Switch over the interruption type.
        switch type {
            case .began:
                try? AVAudioSession.sharedInstance().setActive(false)
                // An interruption began. Update the UI as needed.
                audioStreamHandler.push(data: ["type": "interruptionBegan"])
                break
            case .ended:
               // An interruption ended. Resume playback, if appropriate.
                try? AVAudioSession.sharedInstance().setActive(true)
                guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    // Interruption ended. Playback should resume.
                    audioStreamHandler.push(data: ["type": "interruptionEndedWithResume"])
                } else {
                    // Interruption ended. Playback should not resume.
                    audioStreamHandler.push(data: ["type": "interruptionEndedWithoutResume"])
                }
                break
            default:
                break
        }
    }
    
    // developer.apple.com/documentation/coreaudiotypes/coreaudiotype_constants/1572096-audio_data_format_identifiers
    func getOutputFormatFromString(_ format : String) -> Int {
        switch format {
        case ".mp4", ".aac", ".m4a":
            return Int(kAudioFormatMPEG4AAC)
        case ".wav":
            return Int(kAudioFormatLinearPCM)
        default :
            return Int(kAudioFormatMPEG4AAC)
        }
    }
}

class AudioStreamHandler: NSObject, FlutterStreamHandler {
    private var _eventSink: FlutterEventSink?

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        _eventSink = events
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        _eventSink = nil
        return nil
    }
    
    func push(data: [String : Any?]) {
        _eventSink?(data)
    }
}

func mergeAudioFiles(outputUrl: URL, audioFileUrls: [URL], callback: @escaping (_ url: URL?, _ error: Error?)->()) {

    // Create the audio composition
    let composition = AVMutableComposition()
    let compositionAudioTrack = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid)
    
    // Merge
    for url in audioFileUrls {
        compositionAudioTrack?.append(url: url)
    }

    // Export it
    let assetExport = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A)
    assetExport?.outputFileType = AVFileType.m4a
    assetExport?.outputURL = outputUrl
    assetExport?.exportAsynchronously(completionHandler: {
        switch assetExport!.status {
        case AVAssetExportSessionStatus.failed:
            callback(nil, assetExport?.error)
            default:
                callback(assetExport?.outputURL, nil)
        }
    })
}

extension AVMutableCompositionTrack {
    func append(url: URL) {
        let newAsset = AVURLAsset(url: url)
        let range = CMTimeRangeMake(kCMTimeZero, newAsset.duration)
        let end = timeRange.end
        print(end)
        if let track = newAsset.tracks(withMediaType: AVMediaType.audio).first {
            try! insertTimeRange(range, of: track, at: end)
        }
        
    }
}
