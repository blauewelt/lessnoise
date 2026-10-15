import LessNoiseKit
import SwiftUI

/** «Das Telefon liegt …» (placementLabel of ShareScreen.kt). */
func placementLabel(_ p: String) -> String {
    switch p {
    case SiteSettings.PLACEMENT_OPEN_WINDOW: return "am offenen Fenster"
    case SiteSettings.PLACEMENT_BALCONY: return "auf dem Balkon"
    case SiteSettings.PLACEMENT_BEHIND_GLASS: return "hinter Glas"
    default: return "anderswo"
    }
}

/**
 * «Teilen» (ShareScreen.kt of 0.6.3): the switch and the status line on top; «Weitere Angaben»
 * (Nur über WLAN, Noch zu senden, Standort verschieben); «Was gesendet wird»; «Wo liegt das
 * Telefon? (freiwillig)»; the reports; deleting the data on the server.
 */
struct ShareView: View {
    @EnvironmentObject var model: AppModel
    @State private var showMore = false
    @State private var showExplanation = false
    @State private var showPlacement = false
    @State private var confirmDelete = false
    @State private var confirmNewId = false
    @State private var busy: String?
    @State private var result: String?

    var body: some View {
        let s = model.upload
        let share = model.services.share
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ScreenTitle(text: "Auf die Lärmkarte")
                Text(ShareTexts.SHARE_INTRO).font(.subheadline).padding(.horizontal, 16)
                SectionCard {
                    Toggle("Auf die Karte senden", isOn: Binding(get: { s.enabled }, set: { on in
                        if on { share.switchOn(ask: false) } else { share.switchOff() }
                    }))
                    if let st = model.shareStatus { StatusLine(status: st, onTap: { model.handle($0) }) }
                    if s.refusedTotal > 0 {
                        Text("\(s.refusedTotal) Werte waren zu alt und wurden nicht übernommen.").font(.footnote)
                    }
                    if s.enabled && s.authFailed {
                        Text(UploadRules.AUTH_FAILED_TEXT).font(.footnote).foregroundColor(.red)
                    }
                    Button(showMore ? "Weitere Angaben ▾" : "Weitere Angaben ▸") { showMore.toggle() }.font(.subheadline)
                    if showMore {
                        Toggle("Nur über WLAN", isOn: Binding(get: { s.wifiOnly }, set: { v in
                            model.services.uploadStore.update { var n = $0; n.wifiOnly = v; return n }
                            model.services.uploads.applySchedule()
                        }))
                        if !s.wifiOnly { Text("Höchstens etwa 3 MB pro Tag.").font(.footnote) }
                        StatRow(label: "Noch zu senden", value: "\(pendingMinutes(s)) Minuten")
                        Text(ShareTexts.SIGMA_LABEL).font(.subheadline)
                        Picker(ShareTexts.SIGMA_LABEL, selection: Binding(get: { s.sigmaM }, set: { v in
                            model.services.uploadStore.update { var n = $0; n.sigmaM = v; return n }
                        })) {
                            ForEach(LocationNoise.SIGMAS_M, id: \.self) { Text(ShareTexts.sigmaChoice($0)).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        Text(ShareTexts.SIGMA_EXPLANATION).font(.footnote)
                    }
                }
                FoldButton(title: "Was gesendet wird", open: $showExplanation)
                if showExplanation { SectionCard { ShareExplanation(sigmaM: s.sigmaM) } }
                FoldButton(title: "Wo liegt das Telefon? (freiwillig)", open: $showPlacement)
                if showPlacement {
                    PlacementCard(current: s.site) { site in
                        model.services.uploadStore.update { var n = $0; n.site = site; return n }
                        model.services.uploads.uploadSoon()
                    }
                }
                ReportsCard(reports: model.services.reportStore.state)
                SectionCard(title: "Deine Daten auf dem Server") {
                    if !s.registered {
                        Text("Von diesem Telefon liegen keine Daten auf dem Server.").font(.footnote)
                    }
                    Button("Vom Server löschen", role: .destructive) { confirmDelete = true }
                        .buttonStyle(.borderedProminent).tint(.red)
                        .disabled(!s.registered || busy != nil)
                    if s.authFailed {
                        Button("Neu starten") { confirmNewId = true }.buttonStyle(.bordered).disabled(busy != nil)
                    }
                    if let b = busy { Text(b).font(.footnote); ProgressView() }
                    if let r = result { Text(r).font(.footnote) }
                }
                Spacer(minLength: 24)
            }
        }
        .alert("Daten auf dem Server löschen?", isPresented: $confirmDelete) {
            Button("Löschen", role: .destructive) { runDelete(newIdentity: false) }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Alles, was dieses Telefon gesendet hat, wird sofort gelöscht. Teilen wird ausgeschaltet. Auf dem Telefon bleibt alles.")
        }
        .alert("Neu starten?", isPresented: $confirmNewId) {
            Button("Neu starten") { runDelete(newIdentity: true) }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Deine alten Daten auf dem Server werden gelöscht; danach sendest du neu.")
        }
    }

    private func pendingMinutes(_ s: UploadSnapshot) -> Int {
        (try? model.services.measurements.sharedMinuteCountAfter(model.services.uploads.uploader.minutesPendingAfterMs(s))) ?? 0
    }

    private func runDelete(newIdentity: Bool) {
        busy = "Wird gelöscht …"
        result = nil
        Task {
            let r: Uploader.DeleteOutcome
            if newIdentity { r = await model.services.uploads.newIdentity() } else { r = await model.services.uploads.deleteServerData() }
            busy = nil
            switch r {
            case .deleted: result = newIdentity ? "Neu gestartet." : "Vom Server gelöscht. Teilen ist aus."
            case .unknownToServer: result = "Auf dem Server liegt nichts mehr von diesem Telefon."
            case .failed: result = "Nicht gelöscht. Bitte mit Internet nochmals versuchen."
            }
        }
    }
}

/** The full explanation (what is sent, location, never sent, where, when, delete). */
struct ShareExplanation: View {
    let sigmaM: Int
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            block("Was gesendet wird", ShareTexts.SHARE_SENT_BULLETS)
            block("Standort", ShareTexts.shareLocationBullets(sigmaM))
            block("Nie gesendet", ["Audio, Name, Telefonnummer oder E-Mail.", ShareTexts.LOCATION_NEVER_SENT])
            block("Wohin", ShareTexts.SHARE_WHERE_BULLETS)
            block("Wann", ShareTexts.SHARE_WHEN_BULLETS)
            block("Löschen", ShareTexts.SHARE_DELETE_BULLETS)
            MoreLink(url: Links.PRIVACY, label: "Datenschutz")
        }
    }

    private func block(_ title: String, _ lines: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline.weight(.semibold))
            ForEach(lines, id: \.self) { Text("• " + $0).font(.footnote) }
        }
    }
}

/** «Wo liegt das Telefon? (freiwillig)»: remembered for the next measurements. */
private struct PlacementCard: View {
    let current: SiteSettings
    let onSave: (SiteSettings) -> Void
    @State private var placement = SiteSettings.PLACEMENT_OPEN_WINDOW
    @State private var facing = 2
    @State private var floorText = ""
    @State private var note = ""
    @State private var more = false
    @State private var saved = false
    @State private var loaded = false

    var body: some View {
        let floor = Int(floorText.trimmingCharacters(in: .whitespaces))
        let floorOk = floorText.trimmingCharacters(in: .whitespaces).isEmpty || (floor != nil && (-3...100).contains(floor!))
        SectionCard {
            Text("Das Telefon liegt").font(.subheadline)
            Picker("Das Telefon liegt", selection: $placement) {
                ForEach(SiteSettings.PLACEMENTS, id: \.self) { Text(placementLabel($0)).tag($0) }
            }
            .pickerStyle(.menu)
            Text("Richtung").font(.subheadline)
            Picker("Richtung", selection: $facing) {
                Text("Strassenseite").tag(0); Text("Hofseite").tag(1); Text("weiss nicht").tag(2)
            }
            .pickerStyle(.segmented)
            Button(more ? "Weitere Angaben ▾" : "Weitere Angaben ▸") { more.toggle() }.font(.subheadline)
            if more {
                TextField("Stockwerk (freiwillig)", text: $floorText).keyboardType(.numbersAndPunctuation)
                    .textFieldStyle(.roundedBorder)
                    .foregroundColor(floorOk ? .primary : .red)
                TextField("Notiz (freiwillig, nie öffentlich)", text: $note, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: note) { v in if v.count > SiteSettings.NOTE_MAX { note = String(v.prefix(SiteSettings.NOTE_MAX)) } }
                Text("\(note.count)/\(SiteSettings.NOTE_MAX)").font(.caption).foregroundColor(.secondary)
            }
            Button("Speichern") {
                onSave(SiteSettings(placement: placement, floor: floor, streetFacing: facing == 0 ? true : (facing == 1 ? false : nil),
                                    note: note.trimmingCharacters(in: .whitespacesAndNewlines)))
                saved = true
            }
            .buttonStyle(.borderedProminent)
            .disabled(!floorOk)
            if saved { Text("Gespeichert.").font(.footnote) }
        }
        .onAppear {
            if loaded { return }
            loaded = true
            placement = current.placement
            facing = current.streetFacing == true ? 0 : (current.streetFacing == false ? 1 : 2)
            floorText = current.floor.map(String.init) ?? ""
            note = current.note
            more = current.floor != nil || !current.note.isEmpty
        }
        .onChange(of: placement) { _ in saved = false }
        .onChange(of: facing) { _ in saved = false }
    }
}

/** «Meldungen»: waiting and sent reports (sent ones without coordinates). */
private struct ReportsCard: View {
    let reports: ReportSnapshot
    var body: some View {
        if !reports.pending.isEmpty || !reports.sent.isEmpty {
            SectionCard(title: "Meldungen") {
                if !reports.pending.isEmpty { StatRow(label: "Noch zu senden", value: String(reports.pending.count)) }
                StatRow(label: "Gesendet", value: String(reports.sent.count))
                ForEach(Array(reports.sent.suffix(5).reversed().enumerated()), id: \.offset) { _, s in
                    Text("\(Fmt.dateTime(s.epochMs)) · \(ReportCategory.fromId(s.category)?.label ?? s.category)").font(.footnote)
                }
            }
        }
    }
}
