import Foundation

/**
 * Percentile levels (Percentiles.kt). `Ln` is the level exceeded n % of the time, i.e. the
 * (100 − n)th percentile of the level distribution — so L10 ≥ L50 ≥ L90.
 */
public enum Percentiles {
    /** Linear-interpolated percentile (0…100) of an already sorted array. */
    public static func percentileSorted(_ sorted: [Double], _ p: Double) -> Double {
        precondition(p >= 0.0 && p <= 100.0)
        if sorted.isEmpty { return .nan }
        if sorted.count == 1 { return sorted[0] }
        let pos = p / 100.0 * Double(sorted.count - 1)
        let lo = JavaMath.toInt(pos)
        let hi = Swift.min(lo + 1, sorted.count - 1)
        let frac = pos - Double(lo)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** Level exceeded [n] percent of the time. */
    public static func exceedanceLevelSorted(_ sorted: [Double], _ n: Double) -> Double { percentileSorted(sorted, 100.0 - n) }

    public static func exceedanceLevel(_ values: [Double], _ n: Double) -> Double {
        exceedanceLevelSorted(values.sorted(), n)
    }

    public struct Stats: Equatable {
        public let min: Double, max: Double, l1: Double, l10: Double, l50: Double, l90: Double
    }

    public static func stats(_ values: [Double]) -> Stats {
        if values.isEmpty { return Stats(min: .nan, max: .nan, l1: .nan, l10: .nan, l50: .nan, l90: .nan) }
        let s = values.sorted()
        return Stats(
            min: s.first!, max: s.last!,
            l1: exceedanceLevelSorted(s, 1.0),
            l10: exceedanceLevelSorted(s, 10.0),
            l50: exceedanceLevelSorted(s, 50.0),
            l90: exceedanceLevelSorted(s, 90.0)
        )
    }
}

/** Fixed-capacity ring of doubles (used for the trailing background window). */
public final class DoubleRing {
    public let capacity: Int
    private var data: [Double]
    private var next = 0
    public private(set) var size = 0

    public init(capacity: Int) {
        self.capacity = capacity
        data = [Double](repeating: 0, count: capacity)
    }

    public func add(_ v: Double) {
        data[next] = v
        next = (next + 1) % capacity
        if size < capacity { size += 1 }
    }

    public func toArray() -> [Double] {
        var out = [Double](repeating: 0, count: size)
        let start = (next - size + capacity) % capacity
        for i in 0..<size { out[i] = data[(start + i) % capacity] }
        return out
    }

    public func clear() { next = 0; size = 0 }
}
