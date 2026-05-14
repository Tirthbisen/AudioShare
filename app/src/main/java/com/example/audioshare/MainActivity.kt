package com.example.audioshare

import android.graphics.Color
import org.concentus.OpusDecoder
import android.media.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private var isRunning = false

    private lateinit var statusText     : TextView
    private lateinit var serverIpText   : TextView
    private lateinit var packetCountText: TextView
    private lateinit var latencyText    : TextView
    private lateinit var droppedText    : TextView
    private lateinit var dataText       : TextView
    private lateinit var statusDot      : View

    private var packetCount  = 0L
    private var droppedCount = 0L
    private var totalBytes   = 0L

    companion object {
        const val DISCOVERY_PORT = 5353
        const val AUDIO_PORT     = 59100
        const val TAG            = "AudioShare"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText      = findViewById(R.id.statusText)
        serverIpText    = findViewById(R.id.serverIpText)
        packetCountText = findViewById(R.id.packetCountText)
        latencyText     = findViewById(R.id.latencyText)
        droppedText     = findViewById(R.id.droppedText)
        dataText        = findViewById(R.id.dataText)
        statusDot       = findViewById(R.id.statusDot)

        isRunning = true
        thread(start = true) {
            try {
                val config = discoverServer()
                if (config != null) {
                    runOnUiThread {
                        statusDot.setBackgroundColor(Color.parseColor("#00FF88"))
                        statusText.text = "Streaming"
                        statusText.setTextColor(Color.parseColor("#00FF88"))
                        serverIpText.text = "Server: ${config.ip}"
                    }
                    runAudioEngine(config)
                } else {
                    runOnUiThread {
                        statusDot.setBackgroundColor(Color.parseColor("#FF4444"))
                        statusText.text = "Server not found"
                        statusText.setTextColor(Color.parseColor("#FF4444"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Crash: ${e.message}")
                runOnUiThread {
                    statusDot.setBackgroundColor(Color.parseColor("#FF4444"))
                    statusText.text = "Error: ${e.message}"
                    statusText.setTextColor(Color.parseColor("#FF4444"))
                }
            }
        }
    }

    private fun discoverServer(): ServerConfig? {
        runOnUiThread {
            statusDot.setBackgroundColor(Color.parseColor("#FFAA00"))
            statusText.text = "Searching for server..."
            statusText.setTextColor(Color.parseColor("#FFAA00"))
        }
        return try {
            val socket = DatagramSocket(DISCOVERY_PORT)
            socket.soTimeout = 10000
            val buf    = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val json = JSONObject(String(packet.data, 0, packet.length))
            if (json.getString("service") == "AudioShare") {
                socket.close()
                ServerConfig(
                    ip         = json.getString("ip"),
                    port       = json.getInt("port"),
                    sampleRate = json.getInt("sampleRate"),
                    bitDepth   = json.getInt("bitDepth"),
                    channels   = json.getInt("channels")
                )
            } else {
                socket.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed: ${e.message}")
            null
        }
    }

    private fun runAudioEngine(config: ServerConfig) {
        val channelMask = if (config.channels == 2)
            AudioFormat.CHANNEL_OUT_STEREO
        else
            AudioFormat.CHANNEL_OUT_MONO

        // Opus always outputs 16-bit PCM regardless of source
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioTrack.getMinBufferSize(config.sampleRate, channelMask, encoding)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            return
        }

        runOnUiThread {
            findViewById<TextView>(R.id.sampleRateValue).text = "${config.sampleRate} Hz"
            findViewById<TextView>(R.id.bitDepthValue).text   = "Opus"
            findViewById<TextView>(R.id.channelsValue).text   = if (config.channels == 2) "Stereo" else "Mono"
        }

        audioTrack.play()

        val socket       = DatagramSocket(config.port)
        val buffer       = ByteArray(65536)
        var lastSeq      = -1
        var lastUiUpdate = System.currentTimeMillis()
        var lastLatency  = 0L

        val opusDecoder  = OpusDecoder(config.sampleRate, config.channels)
        val pcmBuffer    = ShortArray(960 * config.channels)

        while (isRunning) {
            val packet     = DatagramPacket(buffer, buffer.size)
            val timeBefore = System.currentTimeMillis()
            socket.receive(packet)
            lastLatency    = System.currentTimeMillis() - timeBefore

            // Sequence number + drop detection
            val seq = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (lastSeq != -1 && seq != (lastSeq + 1) % 65536) droppedCount++
            lastSeq = seq

            // Decode Opus → PCM shorts
            val opus    = packet.data.copyOfRange(2, packet.length)
            val decoded = opusDecoder.decode(opus, opus.size, pcmBuffer, 960, false)

            // Convert ShortArray → ByteArray for AudioTrack
            val pcmBytes = ByteArray(decoded * config.channels * 2)
            for (i in 0 until decoded * config.channels) {
                pcmBytes[i * 2]     = (pcmBuffer[i].toInt() and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = (pcmBuffer[i].toInt() shr 8).toByte()
            }

            audioTrack.write(pcmBytes, 0, pcmBytes.size)

            packetCount++
            totalBytes += packet.length

            val now = System.currentTimeMillis()
            if (now - lastUiUpdate > 500) {
                lastUiUpdate = now
                val kb = totalBytes / 1024
                val display = if (kb > 1024) "${kb / 1024} MB" else "$kb KB"
                val lat = lastLatency
                runOnUiThread {
                    packetCountText.text = packetCount.toString()
                    latencyText.text     = "$lat ms"
                    droppedText.text     = droppedCount.toString()
                    dataText.text        = display
                }
            }
        }

        audioTrack.stop()
        audioTrack.release()
        socket.close()
        Log.d(TAG, "Engine stopped cleanly")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}