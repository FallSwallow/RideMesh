import SwiftUI

@main
struct RideMeshApp: App {
    @StateObject private var ride = RideModel()

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(ride)
        }
    }
}
