import SwiftUI
import UIKit

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
                    TextField("32 字元群組金鑰", text: $ride.keyText)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .disabled(ride.active)
                    HStack {
                        Button("複製") { UIPasteboard.general.string = ride.keyText }
                            .buttonStyle(.borderless)
                            .disabled(ride.keyText.isEmpty)
                        Spacer()
                        Button("清空") { ride.keyText = "" }
                            .buttonStyle(.borderless)
                            .disabled(ride.active || ride.keyText.isEmpty)
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
                        .accessibilityLabel("關於與連線診斷")
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
                .environmentObject(ride)
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
    @EnvironmentObject private var ride: RideModel
    @Environment(\.dismiss) private var dismiss

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
    }

    private var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "未知"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("軟體與安全") {
                    LabeledContent("版本", value: "\(version)（建置 \(build)）")
                    LabeledContent("通訊協定", value: "RideMesh v\(Wire.protocolVersion)")
                    LabeledContent("封包加密", value: Wire.cipherName)
                    Text("語音與成員名稱皆加密；連線使用共享群組金鑰與 Nearby 驗證碼確認。")
                }
                Section("連線診斷") {
                    LabeledContent("目前狀態", value: ride.active ? ride.status : "未通話")
                    if ride.active {
                        LabeledContent("頻道識別碼", value: ride.roomID)
                        LabeledContent("直接連線鄰居", value: "\(ride.directPeers)")
                        LabeledContent("可達頻道成員", value: "\(ride.members.count)")
                        LabeledContent("麥克風", value: ride.muted ? "靜音" : "開啟")
                    }
                }
                Section("連線方式") {
                    Text("使用 Nearby Connections 讓附近裝置直接通訊，不需另架伺服器。")
                    Text("同一頻道的手機需使用 v2 協定；100 公尺與鎖屏重連仍需實機驗證。")
                }
            }
            .navigationTitle("關於 RideMesh")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("完成") { dismiss() } }
        }
    }
}
