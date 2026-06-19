import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        MainViewControllerKt.applicationDidLaunch()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}