import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var ride: RideModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                Section("出發前") {
                    Text("先將安全帽耳機與這支手機配對。所有騎士使用同一組群組金鑰。")
                    TextField("32 字元群組金鑰", text: $ride.keyText)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    Button("建立新群組金鑰") { ride.createGroup() }
                    Button("複製群組金鑰") { UIPasteboard.general.string = ride.keyText }
                        .disabled(ride.keyText.isEmpty)
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
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { ride.resumeIfNeeded() }
        }
    }
}
