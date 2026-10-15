import LessNoiseKit
import SwiftUI

/**
 * The tabs «Messen · Messungen · Karte · Teilen · Einstellungen» (appScreens of the Online-Version).
 * iOS has no back gesture between tabs: a tab opened from inside the app (status line → «Karte»,
 * «Unterbrochen» → «Teilen») shows «‹ Zurück», which returns to the tab before (TabNavigator).
 */
struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let tabs = model.services.tabs
        let screens = appScreens(uploadAvailable: true, mapAvailable: true)
        VStack(spacing: 0) {
            if tabs.showsBack {
                HStack {
                    Button { tabs.back() } label: { Label("Zurück", systemImage: "chevron.backward") }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                    Spacer()
                }
                .background(Color(.systemBackground))
            }
            TabView(selection: Binding(get: { tabs.current }, set: { tabs.select($0) })) {
                ForEach(screens, id: \.self) { s in
                    content(s)
                        .tabItem { Label(screenLabel(s), systemImage: icon(s)) }
                        .tag(s)
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                Text(t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 64)
            }
        }
        .sheet(item: Binding(get: { model.sheet }, set: { if $0 == nil { model.dismissSheet() } })) { sheet in
            switch sheet {
            case let .consent(start): ConsentSheet(startMeasurement: start).environmentObject(model).interactiveDismissDisabled(false)
            case .locationOptions: LocationOptionsSheet().environmentObject(model)
            case .paste: PasteSheet().environmentObject(model)
            case .report: ReportSheet().environmentObject(model)
            }
        }
    }

    @ViewBuilder
    private func content(_ s: Screen) -> some View {
        switch s {
        case .MEASURE: MeasureView()
        case .MEASUREMENTS: MeasurementsView(history: model.history)
        case .MAP: MapTabView(map: model.map, visible: model.services.tabs.current == .MAP)
        case .SHARE: ShareView()
        case .SETTINGS: SettingsView()
        }
    }

    private func icon(_ s: Screen) -> String {
        switch s {
        case .MEASURE: return "waveform"
        case .MEASUREMENTS: return "chart.xyaxis.line"
        case .MAP: return "map"
        case .SHARE: return "square.and.arrow.up"
        case .SETTINGS: return "gearshape"
        }
    }
}
