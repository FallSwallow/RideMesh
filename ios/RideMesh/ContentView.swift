import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var ride: RideModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showQRCode = false
    @State private var showScanner = false
    @State private var showAbout = false
    @State private var qrKey = ""
    @State private var qrNotice = ""
    @State private var scanNotice: String?
    @State private var showQrNotice = false

    var body: some View {
        NavigationStack {
            Form {
                Section("出發前") {
                    Text("先將安全帽耳機與這支手機配對。所有騎士使用同一組群組金鑰。")
                    LabeledContent("群組金鑰") {
                        Text(ride.keyText.isEmpty ? "尚未建立或掃描" : ride.keyText)
                            .font(.system(.body, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                    }
                    Button("建立新群組金鑰") { ride.createGroup() }
                        .disabled(ride.active)
                    HStack {
                        Button("顯示 QR Code") {
                            guard let key = Wire.parseKey(ride.keyText) else {
                                qrNotice = "請先建立或輸入有效的群組金鑰"
                                showQrNotice = true
                                return
                            }
                            qrKey = key.hex
                            showQRCode = true
                        }
                        .buttonStyle(.borderless)
                        Spacer()
                        Button("掃描 QR Code") {
                            scanNotice = nil
                            showScanner = true
                        }
                            .buttonStyle(.borderless)
                            .disabled(ride.active)
                    }
                    TextField("顯示名稱（最多 20 字）", text: $ride.nameText)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .disabled(ride.active)
                }
                if ride.active {
                    Section("頻道成員（\(ride.members.count)）") {
                        ForEach(ride.members) { member in
                            if member.isSelf {
                                Text("\(member.name)（我）")
                            } else {
                                Text("\(member.name)（\(String(member.id.suffix(4)))）")
                            }
                        }
                    }
                }
                Section("通話") {
                    Text(ride.status)
                    if ride.active {
                        Text("群組：\(ride.roomID)")
                        Text("直接連線鄰居：\(ride.directPeers)")
                        Toggle("麥克風靜音", isOn: Binding(
                            get: { ride.muted }, set: { ride.setMuted($0) }
                        ))
                        Button("結束通話", role: .destructive) { ride.stop() }
                    } else {
                        Button("開始對講") { ride.start() }
                    }
                }
                Section {
                    Text("請在停車時完成設定。此原型尚未驗證 100 公尺、鎖屏重連及五人騎乘通話。")
                        .font(.footnote)
                }
            }
            .navigationTitle("RideMesh")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAbout = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel("關於 RideMesh")
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { ride.resumeIfNeeded() }
        }
        .sheet(isPresented: $showQRCode) {
            NavigationStack {
                GroupKeyQRCodeView(key: qrKey)
                    .navigationTitle("群組金鑰 QR Code")
                    .toolbar { Button("完成") { showQRCode = false } }
            }
        }
        .sheet(isPresented: $showAbout) {
            RideMeshAboutView()
        }
        .sheet(isPresented: $showScanner, onDismiss: {
            if let message = scanNotice {
                scanNotice = nil
                qrNotice = message
                showQrNotice = true
            }
        }) {
            NavigationStack {
                GroupKeyScannerView(onScan: { value in
                    showScanner = false
                    if let key = Wire.parseKey(value) {
                        ride.keyText = key.hex
                    } else {
                        scanNotice = "QR Code 不是有效的群組金鑰"
                    }
                }, onError: { message in
                    showScanner = false
                    scanNotice = message
                })
                .navigationTitle("掃描群組金鑰")
                .toolbar { Button("取消") { showScanner = false } }
            }
        }
        .alert("QR Code", isPresented: $showQrNotice) {
            Button("好", role: .cancel) { }
        } message: {
            Text(qrNotice)
        }
    }
}

private struct RideMeshAboutView: View {
    @Environment(\.dismiss) private var dismiss

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("版本", value: version)
                    LabeledContent("封包加密", value: Wire.cipherName)
                }
            }
            .navigationTitle("關於 RideMesh")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { dismiss() } }
        }
    }
}
