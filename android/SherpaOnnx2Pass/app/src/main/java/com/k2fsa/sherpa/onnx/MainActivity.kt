package com.k2fsa.sherpa.onnx

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.graphics.Color
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// adb emu avd hostmicon
// to enable microphone inside the emulator
class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private data class OfflineTask(
        val taskIdx: Int,
        val previousText: String,
        val samples: FloatArray
    )

    private lateinit var onlineRecognizer: OnlineRecognizer
    private lateinit var offlineRecognizer: OfflineRecognizer
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private lateinit var tvOfflineStatus: TextView
    private var recordingThread: Thread? = null
    private var offlineThread: Thread? = null
    private val offlineTaskQueue = LinkedBlockingQueue<OfflineTask>()

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    private var samplesBuffer = arrayListOf<FloatArray>()

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var idx: Int = 0
    private var lastText: String = ""

    @Volatile
    private var isRecording: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide the ActionBar for a full-screen terminal-like experience
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        Log.i(TAG, "Start to initialize first-pass recognizer")
        initOnlineRecognizer()
        Log.i(TAG, "Finished initializing first-pass recognizer")

        Log.i(TAG, "Start to initialize second-pass recognizer")
        initOfflineRecognizer()
        Log.i(TAG, "Finished initializing second-pass recognizer")

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }

        textView = findViewById(R.id.my_text)
        tvOfflineStatus = findViewById(R.id.tv_offline_status)
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            samplesBuffer.clear()
            textView.text = ""
            lastText = ""
            idx = 0
            runOnUiThread {
                tvOfflineStatus.text = "OFFLINE: IDLE"
                tvOfflineStatus.setTextColor(Color.parseColor("#666666"))
            }

            offlineTaskQueue.clear()

            offlineThread = thread(true) {
                processOfflineTasks()
            }

            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            runOnUiThread {
                tvOfflineStatus.text = "OFFLINE: IDLE"
                tvOfflineStatus.setTextColor(Color.parseColor("#666666"))
            }
            Log.i(TAG, "Stopped recording")
        }
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")
        val stream = onlineRecognizer.createStream()

        val interval = 0.1 // i.e., 100 ms
        val bufferSize = (interval * sampleRateInHz).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                samplesBuffer.add(samples)

                stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
                while (onlineRecognizer.isReady(stream)) {
                    onlineRecognizer.decode(stream)
                }
                val isEndpoint = onlineRecognizer.isEndpoint(stream)
                var textToDisplay = lastText

                var text = onlineRecognizer.getResult(stream).text
                if (text.isNotBlank()) {
                    textToDisplay = if (lastText.isBlank()) {
                        // textView.text = "${idx}: ${text}"
                        "${idx}: $text"
                    } else {
                        "${lastText}\n${idx}: $text"
                    }
                }

                if (isEndpoint) {
                    onlineRecognizer.reset(stream)

                    if (text.isNotBlank()) {
                        var totalSamples = 0
                        for (a in samplesBuffer) {
                            totalSamples += a.size
                        }
                        val mergedSamples = FloatArray(totalSamples)
                        var i = 0
                        for (a in samplesBuffer) {
                            for (s in a) {
                                mergedSamples[i] = s
                                i += 1
                            }
                        }

                        val n = maxOf(0, mergedSamples.size - 8000)
                        val samplesForSecondPass = mergedSamples.sliceArray(0..n)

                        samplesBuffer.clear()
                        samplesBuffer.add(mergedSamples.sliceArray(n until mergedSamples.size))

                        offlineTaskQueue.offer(
                            OfflineTask(
                                taskIdx = idx,
                                previousText = lastText,
                                samples = samplesForSecondPass
                            )
                        )

                        idx += 1
                    } else {
                        samplesBuffer.clear()
                    }
                }

                runOnUiThread {
                    textView.text = textToDisplay.lowercase()
                }
            }
        }
        stream.release()
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )
        return true
    }

    private fun initOnlineRecognizer() {
        // Please change getModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models

        /*
        5: paraformer bilingual (big)
        0: zipformer bilingual
        9: zipformer zh 14M (small)
        */
        val firstType = 9
        val firstRuleFsts: String?
        firstRuleFsts = null
        Log.i(TAG, "Select model type $firstType for the first pass")
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getModelConfig(type = firstType)!!,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true
        )
        if (firstRuleFsts != null) {
            config.ruleFsts = firstRuleFsts;
        }

        onlineRecognizer = OnlineRecognizer(
            assetManager = application.assets,
            config = config
        )
    }

    private fun initOfflineRecognizer() {
        // Please change getOfflineModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models

        /*
        15: 2024 sense-voice int8
        41: 2025 sense-voice int8
         */
        val secondType = 15
        var secondRuleFsts: String?
        secondRuleFsts = null
        Log.i(TAG, "Select model type $secondType for the second pass")

        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = secondType)!!
        )

        if (secondRuleFsts != null) {
            config.ruleFsts = secondRuleFsts
        }

        offlineRecognizer = OfflineRecognizer(
            assetManager = application.assets,
            config = config
        )
    }

    private fun processOfflineTasks() {
        while (isRecording || offlineTaskQueue.isNotEmpty()) {
            val task = offlineTaskQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

            runOnUiThread {
                tvOfflineStatus.text = "OFFLINE: PROCESSING"
                tvOfflineStatus.setTextColor(Color.WHITE)
            }

            val text = runSecondPassOnSamples(task.samples)
            val updatedText = if (task.previousText.isBlank()) {
                "${task.taskIdx}: $text"
            } else {
                "${task.previousText}\n${task.taskIdx}: $text"
            }

            runOnUiThread {
                lastText = updatedText
                textView.text = lastText.lowercase()
                tvOfflineStatus.text = "OFFLINE: IDLE"
                tvOfflineStatus.setTextColor(Color.parseColor("#666666"))
            }
        }
    }

    private fun runSecondPassOnSamples(samples: FloatArray): String {
        val stream = offlineRecognizer.createStream()
        stream.acceptWaveform(samples, sampleRateInHz)
        offlineRecognizer.decode(stream)
        val result = offlineRecognizer.getResult(stream)
        stream.release()
        return result.text
    }
}
