package com.example.ridemesh

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.content.Context
import android.media.AudioDeviceInfo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal class AudioEngine(context: Context, private val transmit: (ByteArray) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val players = ConcurrentHashMap<String, Player>()
    @Volatile private var running = false
    @Volatile var muted = false
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    fun start() {
        if (running) return
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val headset = manager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (headset != null) manager.setCommunicationDevice(headset)
        val inputFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBytes = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBytes > 0) { "此手機不支援 8 kHz 錄音" }
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(inputFormat).setBufferSizeInBytes(maxOf(minBytes * 2, 1280)).build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "麥克風無法啟動" }
        recorder = record
        running = true
        record.startRecording()
        captureThread = Thread({ captureLoop(record) }, "RideMesh-capture").also { it.start() }
    }

    private fun captureLoop(record: AudioRecord) {
        val frame = ShortArray(160)
        var hangover = 0
        while (running) {
            var offset = 0
            while (offset < frame.size && running) {
                val read = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                offset += read
            }
            if (offset != frame.size || muted) continue
            val level = frame.sumOf { abs(it.toInt()).toLong() } / frame.size
            if (level > 360) hangover = 20 else if (hangover > 0) hangover--
            if (hangover > 0) transmit(G711.encode(frame))
        }
    }

    fun play(origin: String, encoded: ByteArray) {
        if (!running || encoded.size != 160) return
        players.computeIfAbsent(origin) { Player() }.enqueue(G711.decode(encoded))
    }

    fun stop() {
        val wasRunning = running
        running = false
        if (wasRunning) try { recorder?.stop() } catch (_: IllegalStateException) {}
        captureThread?.join(500)
        recorder?.release()
        recorder = null
        captureThread = null
        players.values.forEach(Player::stop)
        players.clear()
        manager.clearCommunicationDevice()
        manager.mode = AudioManager.MODE_NORMAL
    }

    private class Player {
        private val queue = ArrayBlockingQueue<ShortArray>(8)
        private val track: AudioTrack
        @Volatile private var running = true
        private val thread: Thread

        init {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(format).setBufferSizeInBytes(maxOf(min * 2, 1280))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track.play()
            thread = Thread({ loop() }, "RideMesh-playback").also { it.start() }
        }

        fun enqueue(samples: ShortArray) {
            if (!queue.offer(samples)) {
                queue.poll()
                queue.offer(samples)
            }
        }

        private fun loop() {
            while (running) {
                try {
                    val samples = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        fun stop() {
            running = false
            thread.interrupt()
            thread.join(300)
            track.pause()
            track.flush()
            track.release()
        }
    }
}
