import LessNoiseKit
import SwiftUI

/** «Auf die Lärmkarte?»: three lines, «Mehr dazu» unfolds the full explanation, «Ja, auf die Karte» / «Nur auf dem Telefon». */
struct ConsentSheet: View {
    @EnvironmentObject var model: AppModel
    let startMeasurement: Bool
    @State private var more = false

    var body: some View {
        let sigma = model.upload.sigmaM
        let share = model.services.share
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(ShareTexts.consentLines(sigma), id: \.self) { Text($0).font(.subheadline) }
                    if more { ShareExplanation(sigmaM: sigma) } else { Button(ShareTexts.CONSENT_MORE) { more = true } }
                }
                .padding(20)
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    Button { share.consentYes(startMeasurement: startMeasurement) } label: {
                        Text(ShareTexts.CONSENT_YES).frame(maxWidth: .infinity, minHeight: 40)
                    }
                    .buttonStyle(.borderedProminent)
                    Button { share.consentNo(startMeasurement: startMeasurement) } label: {
                        Text(ShareTexts.CONSENT_NO).frame(maxWidth: .infinity, minHeight: 40)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(16)
                .background(.regularMaterial)
            }
            .navigationTitle(ShareTexts.CONSENT_TITLE)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/** No fix for the running measurement: the reason, «Nochmals», «App-Einstellungen», «Koordinaten einfügen». */
struct LocationOptionsSheet: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        let share = model.services.share
        var outcome: LocationPolicy.Outcome? = nil
        if case let .failed(o) = share.locate { outcome = o }
        return NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(outcome.map(LocationPolicy.message) ?? ShareController.NO_POINT_HINT)
                Button { share.wantPoint(prompt: true) } label: { Text("Nochmals").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                if let o = outcome, LocationSettingsHint.forOutcome(o) == .appSettings {
                    Button { model.openSettingsApp() } label: { Text("App-Einstellungen").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                }
                Button { share.showPaste() } label: { Text("Koordinaten einfügen").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                Spacer()
            }
            .padding(20)
            .navigationTitle("Kein Standort")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schliessen") { share.dismiss() } } }
        }
    }
}

/** «Koordinaten einfügen» for the running measurement. */
struct PasteSheet: View {
    @EnvironmentObject var model: AppModel
    @State private var text = ""
    var body: some View {
        let share = model.services.share
        let parsed: CellInput.Parsed? = text.trimmingCharacters(in: .whitespaces).isEmpty ? nil : CellInput.parse(text)
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text("Z. B. aus einer Karten-App: Punkt lange drücken, Koordinaten kopieren.").font(.footnote)
                TextField("Koordinaten, z. B. 47.3769, 8.5417", text: $text).textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation).autocorrectionDisabled()
                switch parsed {
                case .ok?: Text(ShareTexts.pastedText(model.upload.sigmaM)).font(.footnote)
                case let .error(m)?: Text(m).font(.footnote).foregroundColor(.red)
                case nil: EmptyView()
                }
                Spacer()
            }
            .padding(20)
            .navigationTitle("Koordinaten einfügen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { share.dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Übernehmen") { if let p = parsed { share.pasted(p) } }
                        .disabled({ if case .ok? = parsed { return false }; return true }())
                }
            }
        }
    }
}

/** «Laut! Melden» (LocationFlows.kt): consent → «Was war laut?» → one precise fix → «Meldung … – senden?». */
struct ReportSheet: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let flow = model.services.reports
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                switch flow.step {
                case .consent?:
                    Text(ReportTexts.CONSENT_TEXT)
                    Button { flow.consentYes() } label: { Text(ReportTexts.CONSENT_YES).frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                case .category?:
                    if !flow.suggested.isEmpty { Text(ReportTexts.detectedAt(Fmt.hm(flow.timeMs))).font(.footnote) }
                    ForEach(flow.categoryOrder, id: \.self) { c in
                        if flow.suggested.isEmpty || flow.suggested.contains(c) {
                            Button { flow.choose(c) } label: { Text(c.label).frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                        } else {
                            Button { flow.choose(c) } label: { Text(c.label).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                        }
                    }
                case .locating?:
                    Text(LocationPolicy.PROGRESS_TEXT)
                    ProgressView()
                case let .failed(c, o)?:
                    Text(LocationPolicy.message(o))
                    if LocationSettingsHint.forOutcome(o) == .appSettings {
                        Button("App-Einstellungen") { model.openSettingsApp() }.buttonStyle(.bordered)
                    }
                    Button { flow.choose(c) } label: { Text("Nochmals").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                case .confirm?:
                    Text(flow.confirmText ?? "")
                    Button {
                        if flow.send() { model.show(ReportTexts.QUEUED) }
                    } label: { Text("Senden").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                case nil:
                    EmptyView()
                }
                Spacer()
            }
            .padding(20)
            .navigationTitle(title(flow.step))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { flow.cancel() } } }
        }
    }

    private func title(_ s: ReportFlow.Step?) -> String {
        switch s {
        case .consent?: return ReportTexts.CONSENT_TITLE
        case .category?: return ReportTexts.CATEGORY_TITLE
        case let .locating(c)?: return ReportTexts.locatingTitle(c)
        case .failed?: return ReportTexts.FAILED_TITLE
        case .confirm?: return ReportTexts.CONFIRM_TITLE
        case nil: return ""
        }
    }
}
