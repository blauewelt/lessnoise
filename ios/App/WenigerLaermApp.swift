import SwiftUI
import LessNoiseKit

@main
struct WenigerLaermApp: App {
    var body: some Scene {
        WindowGroup {
            Text("Weniger Lärm · \(Fmt.db(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 2)) dB")
        }
    }
}
