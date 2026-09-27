import AVFoundation
import Foundation

final class VoiceEngine {
    private final class PlayerState {
        let node: AVAudioPlayerNode
        var queued = 0
        init(_ node: AVAudioPlayerNode) { self.node = node }
    }

    private let engine = AVAudioEngine()
    private let speechFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000,
                                           channels: 1, interleaved: false)!
    private var converter: AVAudioConverter?
    private var pendingSamples = [Int16]()
    private var hangover = 0
    private var players = [String: PlayerState]()
    private let transmit: (Data) -> Void
    private(set) var running = false
    var muted = false

    init(transmit: @escaping (Data) -> Void) {
        self.transmit = transmit
    }

    func start() throws {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
        try session.setActive(true)
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)
        guard let converter = AVAudioConverter(from: inputFormat, to: speechFormat) else {
            throw NSError(domain: "RideMesh", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "無法轉換麥克風音訊格式"])
        }
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.consume(buffer)
        }
        do {
            try engine.start()
            running = true
        } catch {
            input.removeTap(onBus: 0)
            self.converter = nil
            try? session.setActive(false)
            throw error
        }
    }

    private func consume(_ input: AVAudioPCMBuffer) {
        guard running, let converter else { return }
        let capacity = AVAudioFrameCount(ceil(Double(input.frameLength) * 8000 /
                                              input.format.sampleRate) + 64)
        guard let output = AVAudioPCMBuffer(pcmFormat: speechFormat, frameCapacity: capacity) else { return }
        var supplied = false
        var conversionError: NSError?
        let result = converter.convert(to: output, error: &conversionError) { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return input
        }
        guard result == .haveData, let channel = output.floatChannelData?[0] else { return }
        for index in 0..<Int(output.frameLength) {
            let sample = max(-1, min(1, channel[index]))
            pendingSamples.append(Int16(sample * 32767))
        }
        while pendingSamples.count >= 160 {
            let frame = Array(pendingSamples.prefix(160))
            pendingSamples.removeFirst(160)
            if muted { continue }
            let level = frame.reduce(0) { $0 + abs(Int($1)) } / frame.count
            if level > 360 { hangover = 20 } else if hangover > 0 { hangover -= 1 }
            if hangover > 0 {
                let encoded = G711.encode(frame)
                DispatchQueue.main.async { [transmit] in transmit(encoded) }
            }
        }
    }

    func play(origin: String, encoded: Data) {
        guard running, encoded.count == 160 else { return }
        let state: PlayerState
        if let existing = players[origin] {
            state = existing
        } else {
            let newPlayer = AVAudioPlayerNode()
            engine.attach(newPlayer)
            engine.connect(newPlayer, to: engine.mainMixerNode, format: speechFormat)
            newPlayer.play()
            state = PlayerState(newPlayer)
            players[origin] = state
        }
        guard state.queued < 8 else { return }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: speechFormat, frameCapacity: 160),
              let channel = buffer.floatChannelData?[0] else { return }
        let samples = G711.decode(encoded)
        for index in 0..<samples.count { channel[index] = Float(samples[index]) / 32768 }
        buffer.frameLength = 160
        state.queued += 1
        state.node.scheduleBuffer(buffer) { [weak state] in
            DispatchQueue.main.async { state?.queued = max(0, (state?.queued ?? 1) - 1) }
        }
    }

    func resumeIfNeeded() {
        guard running, !engine.isRunning else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        try? engine.start()
        for player in players.values where !player.node.isPlaying { player.node.play() }
    }

    func stop() {
        guard running else { return }
        running = false
        engine.inputNode.removeTap(onBus: 0)
        for player in players.values {
            player.node.stop()
            engine.detach(player.node)
        }
        players.removeAll()
        engine.stop()
        converter = nil
        pendingSamples.removeAll()
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
