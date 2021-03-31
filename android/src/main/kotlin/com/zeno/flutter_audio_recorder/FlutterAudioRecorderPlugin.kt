package com.zeno.flutter_audio_recorder

import android.Manifest
import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/** FlutterAudioRecorderPlugin  */
class FlutterAudioRecorderPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, RequestPermissionsResultListener {
    private var mSampleRate = 16000 // 16Khz
    private var mRecorder: AudioRecord? = null
    private var mFilePath: String? = null
    private var mExtension: String? = null
    private var bufferSize = 1024
    private var mFileOutputStream: FileOutputStream? = null
    private var mStatus = "unset"
    private var mPeakPower = -120.0
    private var mAveragePower = -120.0
    private var mRecordingThread: Thread? = null
    private var mDataSize: Long = 0
    private var _result: MethodChannel.Result? = null
    private var eventChannel: EventChannel? = null
    private var methodChannel: MethodChannel? = null
    private var audioStreamHandler: AudioStreamHandler? = null
    private var activity: Activity? = null
    private val permissions = arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    private val PERMISSIONS = 200

    // ActivityAware
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        audioStreamHandler!!.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onDetachedFromActivity() {
        activity = null
        audioStreamHandler!!.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        return when (requestCode) {
            PERMISSIONS -> {
                var granted = true
                Log.d(LOG_NAME, "parsing result")
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        Log.d(LOG_NAME, "result$result")
                        granted = false
                    }
                }
                Log.d(LOG_NAME, "onRequestPermissionsResult -$granted")
                if (_result != null) {
                    _result!!.success(granted)
                }
                granted
            }
            else -> {
                Log.d(LOG_NAME, "onRequestPermissionsResult - false")
                false
            }
        }
    }

    private fun hasRecordPermission(): Boolean {
        activity?.let {
            return (ContextCompat.checkSelfPermission(it.applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(it.applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        }

        return false
    }

    // FlutterPlugin
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        audioStreamHandler = AudioStreamHandler()
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_audio_recorder/methods")
        methodChannel?.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "flutter_audio_recorder/events")
        eventChannel?.setStreamHandler(audioStreamHandler)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        audioStreamHandler = null
        methodChannel = null
        eventChannel = null
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        // Log.d(LOG_NAME, "calling " + call.method);
        _result = result
        when (call.method) {
            "hasPermissions" -> handleHasPermission()
            "init" -> handleInit(call, result)
            "current" -> handleCurrent(call, result)
            "start" -> handleStart(call, result)
            "pause" -> handlePause(call, result)
            "resume" -> handleResume(call, result)
            "stop" -> handleStop(call, result)
            "combineFiles" -> combineFiles(call, result)
            else -> result.notImplemented()
        }
    }

    private fun handleHasPermission() {
        activity?.let {
            if (hasRecordPermission()) {
                Log.d(LOG_NAME, "handleHasPermission true")
                if (_result != null) {
                    _result!!.success(true)
                }
            } else {
                Log.d(LOG_NAME, "handleHasPermission false")
                ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSIONS_REQUEST_RECORD_AUDIO)
            }
        }
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        resetRecorder()
        mSampleRate = call.argument<Any>("sampleRate").toString().toInt()
        mFilePath = call.argument<Any>("path").toString()
        mExtension = call.argument<Any>("extension").toString()
        bufferSize = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        mStatus = "initialized"
        val initResult = HashMap<String, Any>()
        initResult["duration"] = 0
        initResult["path"] = mFilePath!!
        initResult["audioFormat"] = mExtension!!
        initResult["peakPower"] = mPeakPower
        initResult["averagePower"] = mAveragePower
        initResult["isMeteringEnabled"] = true
        initResult["status"] = mStatus
        result.success(initResult)
    }

    private fun handleCurrent(call: MethodCall, result: MethodChannel.Result) {
        val currentResult = HashMap<String, Any?>()
        currentResult["duration"] = duration * 1000
        currentResult["path"] = if (mStatus === "stopped") mFilePath else tempFilename
        currentResult["audioFormat"] = mExtension
        currentResult["peakPower"] = mPeakPower
        currentResult["averagePower"] = mAveragePower
        currentResult["isMeteringEnabled"] = true
        currentResult["status"] = mStatus
        // Log.d(LOG_NAME, currentResult.toString());
        result.success(currentResult)
    }

    private fun handleStart(call: MethodCall, result: MethodChannel.Result) {
        val audioFocusResult: Int = audioStreamHandler!!.requestAudioFocus()
        if (audioFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            mFileOutputStream = try {
                FileOutputStream(tempFilename)
            } catch (e: FileNotFoundException) {
                result.error("", "cannot find the file", null)
                return
            }
            mRecorder!!.startRecording()
            mStatus = "recording"
            startThread()
            result.success(null)
        } else {
            Log.w(TAG, "could not get audio focus")
        }
    }

    private fun startThread() {
        mRecordingThread = Thread(Runnable { processAudioStream() }, "Audio Processing Thread")
        mRecordingThread!!.start()
    }

    private fun handlePause(call: MethodCall, result: MethodChannel.Result) {
        mStatus = "paused"
        mPeakPower = -120.0
        mAveragePower = -120.0
        mRecorder!!.stop()
        audioStreamHandler!!.resignAudioFocus()
        mRecordingThread = null
        result.success(null)
    }

    private fun handleResume(call: MethodCall, result: MethodChannel.Result) {
        mStatus = "recording"
        mRecorder!!.startRecording()
        startThread()
        result.success(null)
    }

    private fun handleStop(call: MethodCall, result: MethodChannel.Result) {
        if (mStatus == "stopped") {
            result.success(null)
        } else {
            mStatus = "stopped"

            // Return Recording Object
            val currentResult = HashMap<String, Any?>()
            currentResult["duration"] = duration * 1000
            currentResult["path"] = mFilePath
            currentResult["audioFormat"] = mExtension
            currentResult["peakPower"] = mPeakPower
            currentResult["averagePower"] = mAveragePower
            currentResult["isMeteringEnabled"] = true
            currentResult["status"] = mStatus
            resetRecorder()
            mRecordingThread = null
            mRecorder!!.stop()
            mRecorder!!.release()
            audioStreamHandler!!.resignAudioFocus()
            try {
                mFileOutputStream!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            Log.d(LOG_NAME, "before adding the wav header")
            copyWaveFile(listOf(tempFilename), mFilePath)
            deleteTempFile()

            // Log.d(LOG_NAME, currentResult.toString());
            result.success(currentResult)
        }
    }

    private fun combineFiles(call: MethodCall, result: MethodChannel.Result) {
        call.argument<List<String>>("files")?.let { filesList ->
            call.argument<String>("outputPath")?.let {
                copyWaveFile(filesList, it)
                result.success(it)
            }
        }
    }

    private fun processAudioStream() {
        Log.d(LOG_NAME, "processing the stream: $mStatus")
        val size = bufferSize
        val bData = ByteArray(size)
        while (mStatus === "recording") {
            Log.d(LOG_NAME, "reading audio data")
            mRecorder!!.read(bData, 0, bData.size)
            mDataSize += bData.size.toLong()
            updatePowers(bData)
            try {
                mFileOutputStream!!.write(bData)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun deleteTempFile() {
        val file = File(tempFilename)
        if (file.exists()) {
            file.delete()
        }
    }

    private val tempFilename: String
        get() = "$mFilePath.temp"

//    private fun copyWaveFile(inFilename: String, outFilename: String?) {
//        var `in`: FileInputStream? = null
//        var out: FileOutputStream? = null
//        var totalAudioLen: Long = 0
//        var totalDataLen = totalAudioLen + 36
//        val longSampleRate = mSampleRate.toLong()
//        val channels = 1
//        val byteRate = RECORDER_BPP * mSampleRate * channels / 8.toLong()
//        val data = ByteArray(bufferSize)
//        try {
//            `in` = FileInputStream(inFilename)
//            out = FileOutputStream(outFilename)
//            totalAudioLen = `in`.channel.size()
//            totalDataLen = totalAudioLen + 36
//            writeWaveFileHeader(out, totalAudioLen, totalDataLen,
//                    longSampleRate, channels, byteRate)
//            while (`in`.read(data) != -1) {
//                out.write(data)
//            }
//            `in`.close()
//            out.close()
//        } catch (e: FileNotFoundException) {
//            e.printStackTrace()
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
//    }

    private fun copyWaveFile(inFilenames: List<String>, outFilename: String?) {
        val longSampleRate = mSampleRate.toLong()
        val channels = 1
        val byteRate = RECORDER_BPP * mSampleRate * channels / 8.toLong()
        val data = ByteArray(bufferSize)
        val out = FileOutputStream(outFilename)
        val fileStreams: MutableList<FileInputStream> = mutableListOf()
        var totalAudioLen: Long = 0

        try {
            for (file in inFilenames) {
                fileStreams.add(FileInputStream(file))
                totalAudioLen += fileStreams.last().channel.size()
            }

            writeWaveFileHeader(out, totalAudioLen, totalAudioLen + 36,
                    longSampleRate, channels, byteRate)

            for (inStream in fileStreams) {
                while (inStream.read(data) != -1) {
                    out.write(data)
                }
                inStream.close()
            }
            out.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun writeWaveFileHeader(out: FileOutputStream, totalAudioLen: Long,
                                    totalDataLen: Long, longSampleRate: Long, channels: Int, byteRate: Long) {
        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF/WAVE header
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // 'fmt ' chunk
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 1.toByte() // block align
        header[33] = 0
        header[34] = RECORDER_BPP // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun byte2short(bData: ByteArray): ShortArray {
        val out = ShortArray(bData.size / 2)
        ByteBuffer.wrap(bData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()[out]
        return out
    }

    private fun resetRecorder() {
        mPeakPower = -120.0
        mAveragePower = -120.0
        mDataSize = 0
    }

    private fun updatePowers(bdata: ByteArray) {
        val data = byte2short(bdata)
        val sampleVal = data[data.size - 1]
        val escapeStatusList = arrayOf("paused", "stopped", "initialized", "unset")
        mAveragePower = if (sampleVal.toInt() == 0 || listOf(*escapeStatusList).contains(mStatus)) {
            -120.0 // to match iOS silent case
        } else {
            // iOS factor : to match iOS power level
            val iOSFactor = 0.25
            20 * Math.log(Math.abs(sampleVal.toInt()) / 32768.0) * iOSFactor
        }
        mPeakPower = mAveragePower
        // Log.d(LOG_NAME, "Peak: " + mPeakPower + " average: "+ mAveragePower);
    }

    private val duration: Int
        get() {
            val duration = mDataSize / (mSampleRate * 2 * 1)
            return duration.toInt()
        }

    companion object {
        private const val LOG_NAME = "AndroidAudioRecorder"
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 200
        private const val RECORDER_BPP: Byte = 16 // we use 16bit
    }
}

class AudioStreamHandler : EventChannel.StreamHandler {
    var eventSink: EventChannel.EventSink? = null
    var focusListener : AudioManager.OnAudioFocusChangeListener? = null
    var audioFocusRequest: AudioFocusRequest? = null
    var audioManager: AudioManager? = null
    var activity: Activity? = null

    private fun initializeAudioFocusDependencies() {
        focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Pause playback because your Audio Focus was
                    // temporarily stolen, but will be back soon.
                    // i.e. for a phone call
                    eventSink?.success(mapOf("type" to "interruptionBegan"))
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Stop playback, because you lost the Audio Focus.
                    // i.e. the user started some other playback app
                    // Remember to unregister your controls/buttons here.
                    // And release the kra — Audio Focus!
                    // You’re done.
                    //                    am.abandonAudioFocus(this)
                    eventSink?.success(mapOf("type" to "interruptionBegan"))
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Lower the volume, because something else is also
                    // playing audio over you.
                    // i.e. for notifications or navigation directions
                    // Depending on your audio playback, you may prefer to
                    // pause playback here instead. You do you.
                    eventSink?.success(mapOf("type" to "interruptionBegan"))
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Resume playback, because you hold the Audio Focus
                    // again!
                    // i.e. the phone call ended or the nav directions
                    // are finished
                    // If you implement ducking and lower the volume, be
                    // sure to return it to normal here, as well.
                    eventSink?.success(mapOf("type" to "interruptionEndedWithResume"))
                }
            }
        }
        audioManager = activity!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusListener?.let {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(it)
                        .build()
            }
        } else {
            audioFocusRequest = null
        }
    }

    fun requestAudioFocus() : Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager!!.requestAudioFocus(audioFocusRequest!!)
        }  else {
            audioManager!!.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        }
    }

    fun resignAudioFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager!!.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            audioManager!!.abandonAudioFocus(focusListener)
        }
    }

    // EventChannel.StreamHandler
    override fun onListen(args: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        initializeAudioFocusDependencies()
    }

    override fun onCancel(args: Any?) {
        eventSink = null
    }
}