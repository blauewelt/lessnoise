import Charts
import LessNoiseKit
import SwiftUI

/** «Messungen» data, loaded off the main thread when the database changes (at most every 2 s). */
@MainActor
final class HistoryModel: ObservableObject {
    @Published private(set) var items: [MeasurementItem]? = nil
    @Published var selectedId: Int?
    @Published private(set) var chart: (minutes: [MinuteRecord], events: [NoiseEvent])? = nil
    private let services: AppServices
    private var token: Int?
    private var pending = false
    private let queue = DispatchQueue(label: "ch.lessnoise.history", qos: .userInitiated)

    init(_ services: AppServices) {
        self.services = services
        token = services.db.changes.add { [weak self] in Task { @MainActor in self?.reloadSoon() } }
    }

    func reloadSoon() {
        if pending { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.reload() }
    }

    func reload() {
        pending = false
        let s = services
        let live = s.session.live
        let floor = s.settings.state.eventMinLevelDb
        let selected = selectedId
        let now = s.clock.nowMs()
        queue.async {
            let items = (try? s.history.items(nowMs: now, running: live.running, eventMinLevelDb: floor)) ?? []
            let chosen = items.first { $0.id == selected } ?? items.first
            let data = chosen.flatMap { try? s.history.data($0.measurement) }
            DispatchQueue.main.async {
                self.items = items
                if self.selectedId == nil || !items.contains(where: { $0.id == self.selectedId }) { self.selectedId = chosen?.id }
                self.chart = data
            }
        }
    }

    func select(_ id: Int) {
        selectedId = id
        reload()
    }
}

/** «Messungen»: the chart of the selected measurement and the list of all measurements (MeasurementsScreen.kt). */
struct MeasurementsView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var history: HistoryModel

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ScreenTitle(text: "Messungen").id("top")
                    if let list = history.items {
                        if list.isEmpty {
                            Text("Noch keine Messung. Starte eine unter «Messen».").padding(16)
                        } else {
                            if let sel = list.first(where: { $0.id == history.selectedId }) {
                                SectionCard(title: Fmt.measurementTitle(sel.measurement)) {
                                    MeasurementChart(minutes: history.chart?.minutes ?? [], events: history.chart?.events ?? [],
                                                     floorDb: model.settings.eventMinLevelDb, mapper: model.services.mapper)
                                }
                            }
                            Text("Alle Messungen").font(.headline).padding(.horizontal, 16).padding(.top, 16)
                            ForEach(list) { item in
                                MeasurementCard(item: item, selected: item.id == history.selectedId)
                                    .onTapGesture {
                                        history.select(item.id)
                                        withAnimation { proxy.scrollTo("top", anchor: .top) }
                                    }
                            }
                        }
                    } else {
                        Text("Lade …").padding(16)
                    }
                    Spacer(minLength: 24)
                }
            }
        }
        .onAppear { history.reload() }
    }
}

private struct MeasurementCard: View {
    let item: MeasurementItem
    let selected: Bool

    var body: some View {
        let m = item.measurement
        let s = item.summary
        SectionCard {
            HStack {
                Text(Fmt.measurementTitle(m)).font(.headline.weight(selected ? .bold : .semibold))
                Spacer()
                if m.cell != nil {
                    Text("geteilt").font(.caption).padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            StatRow(label: "Dauer", value: Fmt.hoursMinutes(m.span.durationMs))
            StatRow(label: "Mittel", value: "\(Fmt.db(s.laeqDb)) dB(A)")
            StatRow(label: "Grundrauschen", value: "\(Fmt.db(s.humDb)) dB(A)")
            StatRow(label: "Lärmspitzen pro Stunde", value: Fmt.eventsPerHour(s.eventsPerHour))
        }
        .overlay(selected ? RoundedRectangle(cornerRadius: 14).stroke(Color.accentColor, lineWidth: 2).padding(.horizontal, 16).padding(.vertical, 4) : nil)
    }
}

/**
 * A simple chart of one measurement (Swift Charts): LAeq per minute and the Grundrauschen (L90) as
 * lines, the Lärmspitzen (LAFmax, at or above the floor «Lärmspitzen erst ab») as dots in their
 * category's colour. Minutes with less than half valid audio are left out (as in the summaries).
 */
struct MeasurementChart: View {
    let minutes: [MinuteRecord]
    let events: [NoiseEvent]
    let floorDb: Double
    let mapper: CategoryMapper?

    private struct P: Identifiable { let id: Int; let t: Date; let v: Double; let series: String }

    var body: some View {
        let valid = minutes.filter { $0.coverage >= MeasurementSummarizer.MIN_MINUTE_COVERAGE && !$0.laeqDb.isNaN }
        let laeq = valid.map { P(id: $0.startEpochMs, t: Date(timeIntervalSince1970: Double($0.startEpochMs) / 1000), v: $0.laeqDb, series: "Mittel") }
        let l90 = valid.filter { !$0.l90Db.isNaN }.map { P(id: $0.startEpochMs, t: Date(timeIntervalSince1970: Double($0.startEpochMs) / 1000), v: $0.l90Db, series: "Grundrauschen") }
        let peaks = events.filter { !$0.wind && $0.reachesFloor(floorDb) }
        if valid.isEmpty && peaks.isEmpty {
            Text("Noch keine Minutenwerte.").font(.subheadline).foregroundColor(.secondary)
        } else {
            Chart {
                ForEach(laeq) { p in
                    LineMark(x: .value("Zeit", p.t), y: .value("dB(A)", p.v), series: .value("Reihe", p.series))
                        .foregroundStyle(Color.accentColor)
                        .interpolationMethod(.monotone)
                }
                ForEach(l90) { p in
                    LineMark(x: .value("Zeit", p.t), y: .value("dB(A)", p.v), series: .value("Reihe", p.series))
                        .foregroundStyle(Color.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
                ForEach(Array(peaks.enumerated()), id: \.offset) { _, e in
                    PointMark(x: .value("Zeit", Date(timeIntervalSince1970: Double(e.startEpochMs) / 1000)), y: .value("dB(A)", e.lafMaxDb))
                        .foregroundStyle(Color.category(e.dominantCategory))
                        .symbolSize(24)
                }
            }
            .chartYAxisLabel("dB(A)")
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 5)) { v in
                    AxisGridLine()
                    AxisValueLabel {
                        if let d = v.as(Date.self) { Text(Fmt.hm(Int(d.timeIntervalSince1970 * 1000))) }
                    }
                }
            }
            .frame(height: 220)
            HStack(spacing: 12) {
                Label("Mittel", systemImage: "minus").foregroundColor(.accentColor)
                Label("Grundrauschen", systemImage: "minus").foregroundColor(.secondary)
                Label("Lärmspitzen", systemImage: "circle.fill").foregroundColor(.category("loud_vehicle"))
            }
            .font(.caption)
            .labelStyle(.titleAndIcon)
        }
    }
}
