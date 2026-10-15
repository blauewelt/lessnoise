import Foundation

/** The screens of the tab bar (ui/AppTabs.kt). */
public enum Screen: String, Equatable, CaseIterable {
    case MEASURE, MEASUREMENTS, MAP, SHARE, SETTINGS
}

/**
 * The tabs: «Messen · Messungen · Einstellungen», plus in the editions with the map «Karte» and
 * «Teilen». The iOS app is the Online-Version: both flags true.
 */
public func appScreens(uploadAvailable: Bool, mapAvailable: Bool) -> [Screen] {
    var out: [Screen] = [.MEASURE, .MEASUREMENTS]
    if uploadAvailable && mapAvailable { out.append(.MAP) }
    if uploadAvailable { out.append(.SHARE) }
    out.append(.SETTINGS)
    return out
}

/** German tab label. */
public func screenLabel(_ s: Screen) -> String {
    switch s {
    case .MEASURE: return "Messen"
    case .MEASUREMENTS: return "Messungen"
    case .MAP: return "Karte"
    case .SHARE: return "Teilen"
    case .SETTINGS: return "Einstellungen"
    }
}

/**
 * Which tab "back" returns to (TabHistory in AppTabs.kt): an undo stack of the tabs opened. On
 * iOS there is no system back gesture between tabs; the app uses it for the edge-swipe/back
 * button of its own navigation (ios/DESIGN.md «Tabs»), with the same rule.
 */
public struct TabHistory: Equatable {
    /** How many tabs back can return to. */
    public static let MAX_BACK = 30

    public let stack: [Screen]

    public init(_ stack: [Screen] = [.MEASURE]) {
        precondition(!stack.isEmpty && stack.count <= TabHistory.MAX_BACK + 1, "bad tab history \(stack)")
        self.stack = stack
    }

    /** The tab shown. */
    public var current: Screen { stack.last! }

    /** Whether back stays in the app (false: nothing to go back to). */
    public var canGoBack: Bool { stack.count > 1 }

    /** [s] was chosen (tab bar, or a tap that opens a tab). */
    public func select(_ s: Screen) -> TabHistory {
        s == current ? self : TabHistory(Array((stack + [s]).suffix(TabHistory.MAX_BACK + 1)))
    }

    /** Back: the tab before; unchanged when there is none. */
    public func back() -> TabHistory { canGoBack ? TabHistory(Array(stack.dropLast())) : self }

    /** For state restoration: the tab names. */
    public func save() -> [String] { stack.map { $0.rawValue } }

    /** From [save]; anything unreadable gives the plain history (only «Messen»). */
    public static func restore(_ names: [String]) -> TabHistory {
        let screens = names.map { Screen(rawValue: $0) }
        if screens.isEmpty || screens.count > MAX_BACK + 1 || screens.contains(where: { $0 == nil }) { return TabHistory() }
        return TabHistory(screens.map { $0! })
    }
}
