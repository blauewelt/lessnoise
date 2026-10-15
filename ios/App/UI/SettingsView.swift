import LessNoiseKit
import SwiftUI

/**
 * «Einstellungen» (DataAndSettingsScreens.kt): «Lange messen», «Deine Messungen» (export, delete),
 * «Über die App», «Datenschutz», then «Erweitert» (the two detector options and Kalibrieren).
 */
struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var advanced = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ScreenTitle(text: "Einstellungen")
                    SectionCard(title: "Lange messen") {
                        Text("Telefon ans Ladegerät und die App mit «Starten» laufen lassen; der Bildschirm darf gesperrt sein. Oben zeigt iOS dabei das Mikrofon-Symbol. Beenden mit «Stoppen».")
                            .font(.footnote)
                    }
                    MeasurementsDataCard()
                    SectionCard(title: "Über die App") {
                        Text("Weniger Lärm für iPhone \(DeviceInfo.version) (\(DeviceInfo.build))").font(.subheadline)
                        Text("Offene Software (Apache-2.0) vom Verein Weniger Lärm.").font(.footnote)
                    }
                    SectionCard(title: "Datenschutz") {
                        Text(MeasureTexts.ABOUT_AUDIO).font(.footnote)
                        Text("Ins Internet geht nur, was du unter «Teilen» einschaltest, und die Karte, wenn du sie öffnest.").font(.footnote)
                        Text("Ohne Kalibrierung sind die Werte Richtwerte (± 5 dB).").font(.footnote)
                        if model.services.classifierAvailable {
                            Text("Geräuscherkennung: YAMNet (Google, Apache-2.0).").font(.footnote)
                        }
                        MoreLink(url: Links.PRIVACY)
                    }
                    NavigationLink("Erweitert ›") { AdvancedView() }
                        .buttonStyle(.bordered)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    Spacer(minLength: 24)
                }
            }
            .navigationBarHidden(true)
        }
    }
}

/** «Deine Messungen»: what is stored on this phone, export (CSV via the share sheet), delete. */
private struct MeasurementsDataCard: View {
    @EnvironmentObject var model: AppModel
    @State private var confirmClear = false
    @State private var info: String?
    @State private var exported: ExportedFiles?

    var body: some View {
        let live = model.live
        let minutes = (try? model.services.measurements.minuteCount()) ?? 0
        let events = (try? model.services.measurements.eventCount()) ?? 0
        let recalRunning = model.services.recalibrator.running
        SectionCard(title: "Deine Messungen") {
            Text("Gespeichert auf diesem Telefon: \(minutes) Minuten, \(events) Lärmspitzen").font(.footnote)
            HStack {
                Button("Exportieren") { export() }.buttonStyle(.bordered)
                Button("Alle löschen", role: .destructive) { confirmClear = true }
                    .buttonStyle(.borderedProminent).tint(.red)
                    .disabled(live.running || recalRunning)
            }
            if live.running { Text("Zum Löschen zuerst die Messung stoppen.").font(.footnote) }
            if let i = info { Text(i).font(.footnote) }
            MoreLink(url: Links.DATA)
        }
        .alert("Alle Messungen löschen?", isPresented: $confirmClear) {
            Button("Löschen", role: .destructive) {
                do { try model.services.clearAll(exportDir: model.exportDir); info = "Gelöscht." } catch { info = "Nicht gelöscht." }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Das kann nicht rückgängig gemacht werden. Kalibrierungen bleiben.")
        }
        .sheet(item: $exported) { f in ShareSheet(items: f.urls) }
    }

    private func export() {
        let s = model.services
        let dir = model.exportDir
        let ids = s.bucketIds
        Task {
            let urls = await Task.detached(priority: .userInitiated) {
                (try? CsvExport.write(s.measurements, bucketIds: ids, dir: dir, nowMs: s.clock.nowMs())) ?? []
            }.value
            if urls.isEmpty { info = "Export fehlgeschlagen." } else { exported = ExportedFiles(urls: urls) }
        }
    }
}

/**
 * «Erweitert» (CalibrateScreen.kt): «Lärmspitzen» (the two detector options) and «Kalibrieren»:
 * compare with a sound level meter (30 s), a 1 kHz calibrator (10 s, optional), or a correction by
 * hand; reset; history and its export; «Alte Messungen anpassen».
 */
struct AdvancedView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var reading = ""
    @State private var refNotes = ""
    @State private var calNotes = ""
    @State private var manualOffset = ""
    @State private var manualNotes = ""
    @State private var confirmRecal = false
    @State private var exported: ExportedFiles?

    var body: some View {
        let cal = model.services.calibration
        let st = cal.state
        let busy = st.running != nil
        let live = model.live
        let measuring = live.running || live.starting
        let active = cal.active
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ScreenTitle(text: "Erweitert")
                if live.running { Text("Gilt ab der nächsten Messung.").font(.footnote).foregroundColor(.secondary).padding(.horizontal, 16) }
                SectionCard(title: "Lärmspitzen") {
                    StatRow(label: "Lärmspitze ab … über Grundrauschen", value: "\(Fmt.db(model.settings.eventExcessDb)) dB")
                    Slider(value: Binding(get: { model.settings.eventExcessDb }, set: { v in
                        model.services.settings.update { var n = $0; n.eventExcessDb = EventFloor.roundToHalf(v); return n }
                    }), in: AppSettings.EVENT_EXCESS_MIN...AppSettings.EVENT_EXCESS_MAX, step: 0.5)
                    Text("Kleiner = mehr Spitzen. Standard 6,5 dB.").font(.footnote)
                    StatRow(label: "Lärmspitzen erst ab", value: "\(Fmt.db(model.settings.eventMinLevelDb)) dB(A)")
                    Slider(value: Binding(get: { model.settings.eventMinLevelDb }, set: { v in
                        model.services.settings.update { var n = $0; n.eventMinLevelDb = EventFloor.roundToHalf(v); return n }
                    }), in: AppSettings.EVENT_MIN_LEVEL_MIN...AppSettings.EVENT_MIN_LEVEL_MAX, step: 0.5)
                    Text("Filtert Geräusche am Telefon selbst (Tippen, Atmen). Standard 30 dB(A).").font(.footnote)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Kalibrieren").font(.title3.bold())
                    Text(CalibrationTexts.INTRO).font(.subheadline)
                    MoreLink(url: Links.CALIBRATE)
                }
                .padding(.horizontal, 16).padding(.top, 12)
                SectionCard {
                    Text(CalibrationTexts.activeLine(active)).font(.subheadline)
                    StatRow(label: "Gerät", value: model.services.calibrations.deviceModel)
                    StatRow(label: "Audioquelle", value: AudioSource.labelDe(cal.source))
                    if let info = live.inputInfo { StatRow(label: "Mikrofon", value: info) }
                }
                if let msg = st.message {
                    SectionCard {
                        Text(msg).font(.subheadline)
                        Button("OK") { cal.clearMessage() }
                    }
                }
                recalCard(active: active, measuring: measuring)
                if busy {
                    SectionCard(title: "Misst …") {
                        Text(CalibrationTexts.KEEP_OPEN).font(.footnote)
                        ProgressView(value: st.progress)
                        StatRow(label: "Noch", value: "\(st.remainingSeconds) s")
                        StatRow(label: "Jetzt", value: "\(Fmt.db(st.currentDb)) dB(A)")
                        Button("Abbrechen") { cal.cancel() }.buttonStyle(.bordered)
                    }
                }
                SectionCard(title: "Mit Messgerät vergleichen") {
                    Text("Telefon neben das Messgerät halten, beide gleichzeitig \(st.referenceWindowSeconds) s messen, Wert unten eintragen.").font(.footnote)
                    Button("Messen (\(st.referenceWindowSeconds) s)") { startCal(.REFERENCE) }.buttonStyle(.borderedProminent).disabled(busy)
                    if let r = st.referenceResult {
                        Divider()
                        StatRow(label: "Telefon", value: "\(Fmt.db(r.rawLaeqDb + (active?.offsetDb ?? Acoustics.DEFAULT_CALIBRATION_OFFSET_DB))) dB(A)")
                        TextField("Wert am Messgerät (dB(A))", text: $reading).keyboardType(.decimalPad).textFieldStyle(.roundedBorder)
                        TextField("Notiz (freiwillig)", text: $refNotes).textFieldStyle(.roundedBorder)
                        let ref = Fmt.parseDb(reading)
                        if let ref = ref { StatRow(label: "Korrektur", value: "\(Fmt.db(CalibrationMath.referenceOffset(ref, r.rawLaeqDb), 2)) dB") }
                        ForEach(cal.referenceWarnings(ref), id: \.self) { w in
                            Text("⚠ " + CalibrationTexts.warning(w)).font(.footnote).foregroundColor(.red)
                        }
                        Button("Übernehmen") {
                            if let ref = ref { cal.saveReference(ref, notes: refNotes); reading = ""; refNotes = "" }
                        }
                        .buttonStyle(.borderedProminent).disabled(ref == nil)
                    }
                }
                SectionCard(title: "Mit Kalibrator (1 kHz)") {
                    Text("Kalibrator mit Adapter dicht aufs Mikrofon setzen.").font(.footnote)
                    Picker("Pegel", selection: Binding(get: { st.calibratorNominalDb }, set: { cal.setCalibratorNominal($0) })) {
                        Text("94 dB").tag(94.0); Text("114 dB").tag(114.0)
                    }
                    .pickerStyle(.segmented).disabled(busy)
                    Button("Messen (10 s)") { startCal(.CALIBRATOR) }.buttonStyle(.borderedProminent).disabled(busy)
                    if let r = st.calibratorResult {
                        Divider()
                        StatRow(label: "Frequenz", value: "\(Fmt.db(r.toneFrequencyHz, 0)) Hz")
                        StatRow(label: "Korrektur", value: "\(Fmt.db(CalibrationMath.calibratorOffset(st.calibratorNominalDb, r.rawLaeqDb), 2)) dB")
                        let warnings = cal.calibratorWarnings()
                        ForEach(warnings, id: \.self) { w in Text("⚠ " + CalibrationTexts.warning(w)).font(.footnote).foregroundColor(.red) }
                        TextField("Notiz (freiwillig)", text: $calNotes).textFieldStyle(.roundedBorder)
                        Button("Übernehmen") { cal.saveCalibrator(notes: calNotes); calNotes = "" }
                            .buttonStyle(.borderedProminent)
                            .disabled(warnings.contains(.FREQUENCY_OFF) || warnings.contains(.NOT_TONAL))
                    }
                }
                SectionCard(title: "Korrektur von Hand") {
                    TextField("Korrektur (dB)", text: $manualOffset).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)
                    TextField("Notiz (freiwillig)", text: $manualNotes).textFieldStyle(.roundedBorder)
                    let v = Fmt.parseDb(manualOffset)
                    if let v = v, CalibrationMath.implausible(v) {
                        Text("⚠ " + CalibrationTexts.warning(.IMPLAUSIBLE_OFFSET)).font(.footnote).foregroundColor(.red)
                    }
                    Button("Speichern") {
                        if let v = v { cal.saveManual(v, notes: manualNotes); manualOffset = ""; manualNotes = "" }
                    }
                    .buttonStyle(.borderedProminent).disabled(v == nil || busy)
                }
                SectionCard(title: "Verlauf") {
                    HStack {
                        Button("Exportieren") { exportHistory() }.buttonStyle(.bordered)
                        Button("Zurücksetzen") { cal.resetToDefault() }.disabled(busy)
                    }
                    let history = cal.history
                    if history.isEmpty { Text("Noch keine Kalibrierungen.").font(.footnote) }
                    ForEach(history) { h in
                        Text(historyLine(h)).font(.footnote)
                    }
                }
                Spacer(minLength: 24)
            }
        }
        .navigationTitle("Erweitert")
        .navigationBarTitleDisplayMode(.inline)
        .alert(CalibrationTexts.RECAL_QUESTION, isPresented: Binding(get: { st.recalOffer != nil || confirmRecal }, set: { v in
            if !v { cal.dismissRecalibration(); confirmRecal = false }
        })) {
            Button("Anpassen") {
                if st.recalOffer != nil { cal.confirmRecalibration() } else if let t = recalTarget(active) { model.services.recalibrator.start(t) }
                confirmRecal = false
            }
            .disabled(measuring)
            Button("Nicht jetzt", role: .cancel) { cal.dismissRecalibration(); confirmRecal = false }
        } message: {
            let n = st.recalOffer?.minutes ?? recalTarget(active).map { model.services.recalibrator.counts($0).minutes } ?? 0
            Text(CalibrationTexts.recalScope(n) + (measuring ? "\n" + CalibrationTexts.STOP_FIRST : ""))
        }
        .sheet(item: $exported) { f in ShareSheet(items: f.urls) }
        .onChange(of: scenePhase) { p in if p == .background { cal.onAppStopped() } }
    }

    /** «#3 · 15.10.2026 21:30 · Messgerät · 121,90 dB · iPhone (Messmodus) · Notiz». */
    private func historyLine(_ h: CalibrationEntity) -> String {
        var parts = ["#\(h.id)", Fmt.dateTime(h.createdEpochMs), CalibrationTexts.methodName(h.method), "\(Fmt.db(h.offsetDb, 2)) dB",
                     AudioSource.labelDe(h.audioSource)]
        if !h.notes.isEmpty { parts.append(h.notes) }
        return parts.joined(separator: " · ")
    }

    private func startCal(_ mode: CalMode) {
        switch AudioCapture.permission {
        case .granted: model.services.calibration.start(mode)
        case .undetermined: AudioCapture.requestPermission { ok in if ok { model.services.calibration.start(mode) } }
        default: model.micDenied = true; model.services.session.showError(MeasureTexts.NO_MIC_PERMISSION)
        }
    }

    private func recalTarget(_ active: CalibrationEntity?) -> Recalibration.Target? {
        guard let a = active, a.isCalibrated else { return nil }
        return Recalibration.Target(calibrationId: a.id, offsetDb: a.offsetDb, audioSource: model.services.calibration.source)
    }

    @ViewBuilder
    private func recalCard(active: CalibrationEntity?, measuring: Bool) -> some View {
        let recal = model.services.recalibrator.state
        let target = recalTarget(active)
        if target != nil || recal != .idle {
            SectionCard(title: "Alte Messungen anpassen") {
                if let t = target, recal == .idle {
                    let c = model.services.recalibrator.counts(t)
                    Text(c.minutes + c.events == 0 ? "Alle Messungen sind schon angepasst."
                         : (measuring ? CalibrationTexts.STOP_FIRST : "\(CalibrationTexts.minutes(c.minutes)) noch nicht angepasst."))
                        .font(.footnote)
                    if c.minutes + c.events > 0 {
                        Button("Anpassen") { confirmRecal = true }.buttonStyle(.borderedProminent).disabled(measuring)
                    }
                }
                switch recal {
                case .idle: EmptyView()
                case let .running(done, total):
                    Text(CalibrationTexts.recalRunning(done, total)).font(.footnote)
                    if total == 0 { ProgressView() } else { ProgressView(value: Double(done), total: Double(total)) }
                case let .done(minutes, _, _):
                    Text(CalibrationTexts.recalDone(minutes)).font(.footnote)
                    Button("OK") { model.services.recalibrator.acknowledge() }
                case let .failed(m):
                    Text(CalibrationTexts.recalFailed(m)).font(.footnote).foregroundColor(.red)
                    Button("OK") { model.services.recalibrator.acknowledge() }
                }
            }
        }
    }

    private func exportHistory() {
        guard let json = try? model.services.calibrations.exportJson() else { return }
        let dir = model.exportDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("wenigerlaerm-kalibrierungen.json")
        if (try? Data(json.utf8).write(to: url)) != nil { exported = ExportedFiles(urls: [url]) }
    }
}
