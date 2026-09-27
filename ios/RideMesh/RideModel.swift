import Foundation
import AVFoundation
import Combine
import UIKit

final class RideModel: ObservableObject {
    @Published var keyText = ""
    @Published var nameText = ""
    @Published var status = "尚未啟動"
    @Published var roomID = ""
    @Published var directPeers = 0
    @Published var active = false
    @Published var muted = false
    @Published var members = [ChannelMember]()
    private var router: MeshRouter?
    private var nearby: NearbyMesh?
    private var voice: VoiceEngine?
    private var heartbeat: Timer?

    init() {
        if let saved = UserDefaults.standard.string(forKey: "ride_user_name"), !saved.isEmpty {
            nameText = saved
        } else {
            let device = UIDevice.current
            let genericNames = [device.model, "iPhone", "iPad", "iPod touch"]
            let deviceName = genericNames.contains {
                device.name.localizedCaseInsensitiveCompare($0) == .orderedSame
            } ? nil : device.name
            let suggestion = Wire.suggestedName(deviceName, suffix: Int.random(in: 0...9999))
            nameText = suggestion
            UserDefaults.standard.set(suggestion, forKey: "ride_user_name")
        }
    }

    func createGroup() {
        keyText = Wire.randomBytes(16).hex
    }

    func start() {
        guard let key = Wire.parseKey(keyText) else {
            status = "請輸入 32 字元十六進位群組金鑰"
            return
        }
        guard let name = Wire.normalizedName(nameText) else {
            status = "請填寫顯示名稱（最多 20 字）"
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
            DispatchQueue.main.async {
                if allowed { self?.begin(with: key, name: name) }
                else { self?.status = "請允許麥克風權限" }
            }
        }
    }

    private func begin(with key: Data, name: String) {
        guard !active else { return }
        nameText = name
        UserDefaults.standard.set(name, forKey: "ride_user_name")
        let node = Wire.randomBytes(8)
        let voice = VoiceEngine { [weak self] encoded in
            self?.router?.sendLocal(encoded)
        }
        let router = MeshRouter(
            groupKey: key, nodeID: node, localName: name,
            send: { [weak self] endpoint, data in self?.nearby?.send(endpoint, data: data) },
            play: { [weak voice] origin, audio in voice?.play(origin: origin, encoded: audio) },
            onPeerCount: { [weak self] count in self?.directPeers = count },
            onMembers: { [weak self] currentMembers in self?.members = currentMembers }
        )
        let nearby = NearbyMesh(router: router) { [weak self] message in self?.status = message }
        self.router = router
        self.nearby = nearby
        self.voice = voice
        roomID = router.roomID
        members = router.memberSnapshot()
        do {
            try voice.start()
            nearby.start()
            heartbeat = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
                self?.router?.sendPresence()
                self?.router?.expireMembers()
            }
            active = true
            status = "通話中，正在尋找同群手機"
        } catch {
            stop()
            status = "啟動失敗：\(error.localizedDescription)"
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
        heartbeat?.invalidate()
        heartbeat = nil
        nearby?.stop()
        voice?.stop()
        nearby = nil
        voice = nil
        router = nil
        directPeers = 0
        members = []
        active = false
        status = "通話已結束"
    }
}
