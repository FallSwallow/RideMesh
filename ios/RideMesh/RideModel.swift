import Foundation
import AVFoundation
import Combine

final class RideModel: ObservableObject {
    @Published var keyText = ""
    @Published var status = "尚未啟動"
    @Published var roomID = ""
    @Published var directPeers = 0
    @Published var active = false
    @Published var muted = false
    private var router: MeshRouter?
    private var nearby: NearbyMesh?
    private var voice: VoiceEngine?

    func createGroup() {
        keyText = Wire.randomBytes(16).hex
    }

    func start() {
        guard let key = Wire.parseKey(keyText) else {
            status = "請輸入 32 字元十六進位群組金鑰"
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
            DispatchQueue.main.async {
                if allowed { self?.begin(with: key) }
                else { self?.status = "請允許麥克風權限" }
            }
        }
    }

    private func begin(with key: Data) {
        guard !active else { return }
        let node = Wire.randomBytes(8)
        let voice = VoiceEngine { [weak self] encoded in
            self?.router?.sendLocal(encoded)
        }
        let router = MeshRouter(
            groupKey: key, nodeID: node,
            send: { [weak self] endpoint, data in self?.nearby?.send(endpoint, data: data) },
            play: { [weak voice] origin, audio in voice?.play(origin: origin, encoded: audio) },
            onPeerCount: { [weak self] count in self?.directPeers = count }
        )
        let nearby = NearbyMesh(router: router) { [weak self] message in self?.status = message }
        self.router = router
        self.nearby = nearby
        self.voice = voice
        roomID = router.roomID
        do {
            try voice.start()
            nearby.start()
            active = true
            status = "通話中，正在尋找同群手機"
        } catch {
            status = "啟動失敗：\(error.localizedDescription)"
            stop()
        }
    }

    func setMuted(_ value: Bool) {
        muted = value
        voice?.muted = value
    }

    func resumeIfNeeded() {
        if active { voice?.resumeIfNeeded() }
    }

    func stop() {
        nearby?.stop()
        voice?.stop()
        nearby = nil
        voice = nil
        router = nil
        directPeers = 0
        active = false
        status = "通話已結束"
    }
}
