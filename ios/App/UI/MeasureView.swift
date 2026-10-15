import LessNoiseKit
import SwiftUI

/**
 * «Messen» (MeasureScreen.kt): before «Starten» the placement hint, the microphone line, the button;
 * while measuring the level now, the Grundrauschen, what it sounds like, since when, the status
 * line and the last Lärmspitzen. «Laut! Melden» while no measurement runs; «Unterwegs» under the button.
 */
struct MeasureView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let live = model.live
        let calibrating = model.services.calibration.state.running != nil
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ScreenTitle(text: "Messen")
                if let e = model.storageError {
                    Text(e).font(.subheadline).foregroundColor(.red).padding(.horizontal, 16)
                }
                if !live.running {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(MeasureTexts.PLACEMENT_HINT)
                        Text(MeasureTexts.MIC_LINE)
                        MoreLink(url: Links.MEASURE)
                    }
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                }
                if live.running && (live.micSilenced || live.invalidAudio) {
                    SectionCard {
                        Text(live.micSilenced ? MeasureTexts.SILENCED : MeasureTexts.NO_SIGNAL).foregroundColor(.red).font(.subheadline)
                    }
                }
                if live.running {
                    SectionCard {
                        Text("Jetzt").font(.subheadline)
                        HStack(alignment: .lastTextBaseline, spacing: 8) {
                            Text(Fmt.db(live.lafDb)).font(.system(size: 64, weight: .bold)).monospacedDigit()
                            Text("dB(A)").font(.title3)
                        }
                        LevelBar(db: live.lafDb)
                        StatRow(label: "Grundrauschen", value: "\(Fmt.db(live.backgroundDb)) dB(A)")
                    }
                    SectionCard(title: "Klingt nach") {
                        HStack(spacing: 8) {
                            Circle().fill(Color.category(live.dominantCategory)).frame(width: 14, height: 14)
                            Text(Categories.name(live.dominantCategory, model.services.mapper)).font(.title3)
                        }
                        if !model.services.classifierAvailable {
                            Text("Die Geräuscherkennung kommt auf dem iPhone in einer nächsten Version.").font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    if live.running {
                        Button(role: .destructive) { model.stop() } label: {
                            Text("Stoppen").font(.title3).frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)
                        UnterwegsSwitch()
                        Text(MeasureTexts.runningSince(Fmt.hm(live.startedAtMs))).font(.footnote)
                        if let s = model.shareStatus { StatusLine(status: s, onTap: model.handle) }
                    } else {
                        Button { model.start() } label: {
                            Text(live.starting ? "Startet …" : "Starten").font(.title3).frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(live.starting || calibrating)
                        UnterwegsSwitch()
                        if !live.starting {
                            Button { model.services.reports.begin() } label: {
                                Text(ReportTexts.BUTTON).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    if calibrating { Text(MeasureTexts.CALIBRATING).font(.footnote) }
                    if !live.running && !live.starting, let s = model.shareStatus { StatusLine(status: s, onTap: model.handle) }
                    if let e = live.error {
                        Text(e).foregroundColor(.red).font(.subheadline)
                        if model.micDenied { Button("App-Einstellungen") { model.openSettingsApp() } }
                    }
                }
                .padding(16)
                if live.running {
                    Text("Letzte Lärmspitzen").font(.headline).padding(.horizontal, 16).padding(.vertical, 8)
                    let events = (try? model.services.measurements.recentEvents(since: live.measurementStartMs, limit: 30)) ?? []
                    if events.isEmpty {
                        Text(MeasureTexts.NONE_YET).font(.subheadline).padding(.horizontal, 16)
                    }
                    ForEach(events) { e in
                        EventRow(event: e.event, name: Categories.name(e.event.dominantCategory ?? "n/a", model.services.mapper))
                        Divider().padding(.horizontal, 16)
                    }
                }
                Spacer(minLength: 24)
            }
        }
    }
}

/** «Unterwegs» (LoudAlerts.Switch): off by default; switching it on asks for the notification permission. */
struct UnterwegsSwitch: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Toggle(isOn: Binding(get: { model.settings.unterwegs }, set: { model.setUnterwegs($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LoudAlertWatcher.SWITCH_LABEL)
                    Text(LoudAlertWatcher.SWITCH_HINT).font(.caption).foregroundColor(.secondary)
                }
            }
            if model.settings.unterwegs && model.notificationsDenied {
                Button("Mitteilungen sind aus – App-Einstellungen") { model.openSettingsApp() }.font(.caption)
            }
        }
    }
}
