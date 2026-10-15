import LessNoiseKit
import SwiftUI
import UIKit

extension Color {
    init(rgb: Int) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }

    static func category(_ id: String?) -> Color { Color(rgb: Categories.rgb(id)) }
}

/** A titled card (SectionCard of Common.kt). */
struct SectionCard<Content: View>: View {
    var title: String? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let t = title { Text(t).font(.headline) }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}

struct StatRow: View {
    let label: String
    let value: String
    var body: some View {
        HStack {
            Text(label)
            Spacer(minLength: 8)
            Text(value).fontWeight(.medium).multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }
}

/** The status line on «Messen» and «Teilen» and the map banner (ShareStatusLine). */
struct StatusLine: View {
    let status: ShareStatus
    let onTap: (ShareAction) -> Void

    var body: some View {
        let tappable = status.action != .NONE
        Button { onTap(status.action) } label: {
            HStack {
                Text(status.text).font(.subheadline).multilineTextAlignment(.leading)
                Spacer(minLength: 4)
                if tappable { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)) }
            }
            .foregroundColor(status.problem ? Color(.systemRed) : .primary)
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(status.problem ? Color.red.opacity(0.12) : Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!tappable)
        .accessibilityHint(tappable ? "Antippen" : "")
    }
}

/** 20–100 dB bar (LevelBar of MeasureScreen.kt). */
struct LevelBar: View {
    let db: Double?
    var body: some View {
        let lo = 20.0, hi = 100.0
        let p = (db.map { Swift.min(Swift.max(($0 - lo) / (hi - lo), 0), 1) }) ?? 0
        let color: Color = db == nil ? .accentColor : (db! >= 75 ? .red : (db! >= 55 ? .orange : .green))
        VStack(spacing: 2) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.tertiarySystemFill))
                    Capsule().fill(color).frame(width: g.size.width * p)
                }
            }
            .frame(height: 14)
            HStack {
                Text("20"); Spacer(); Text("60"); Spacer(); Text("100 dB")
            }
            .font(.caption2)
            .foregroundColor(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Pegel \(Fmt.db(db)) dB(A)")
    }
}

/** «Mehr dazu»: opens the page in Safari (the app itself makes no request). */
struct MoreLink: View {
    let url: String
    var label = "Mehr dazu"
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button(label) { if let u = URL(string: url) { openURL(u) } }
            .font(.subheadline)
    }
}

/** A row of «Letzte Lärmspitzen» (EventRow). */
struct EventRow: View {
    let event: NoiseEvent
    let name: String
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color.category(event.dominantCategory)).frame(width: 10, height: 10)
            Text(Fmt.hm(event.startEpochMs)).frame(width: 52, alignment: .leading)
            Text("\(Fmt.db(event.lafMaxDb)) dB(A)").fontWeight(.semibold).frame(width: 96, alignment: .leading)
            Text(name).lineLimit(1)
            Spacer(minLength: 0)
        }
        .font(.subheadline)
        .padding(.horizontal, 16).padding(.vertical, 4)
    }
}

/** The system share sheet for exported files. */
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

struct ExportedFiles: Identifiable {
    let id = UUID()
    let urls: [URL]
}

/** A screen title like Android's headlineSmall. */
struct ScreenTitle: View {
    let text: String
    var body: some View {
        Text(text).font(.title2.bold()).padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
    }
}

/** «▸ / ▾» foldable section header. */
struct FoldButton: View {
    let title: String
    @Binding var open: Bool
    var body: some View {
        Button { open.toggle() } label: { Text(title + (open ? " ▾" : " ▸")) }
            .font(.subheadline)
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
    }
}
