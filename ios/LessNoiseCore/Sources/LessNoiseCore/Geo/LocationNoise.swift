import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto // swift-crypto: the same API as CryptoKit, for the Linux test runs
#endif

/**
 * Gaussian location noise for shared measurements (server/DESIGN.md §2 rule 2, §4.3; port of
 * geo/LocationNoise.kt — read that file for the reasoning).
 *
 * The point sent is the true LV95 point moved by two independent normal draws N(0, σ²) east and
 * north, σ ∈ {25, 50, 100, 200} m (default 50), rounded to whole metres, kept inside the server's
 * box. The draws are deterministic per place: `HMAC-SHA256(secret, "<true hectare id>|<σ>")` under
 * a random 32-byte per-device secret that never leaves the phone; bytes 0–7 and 8–15 as unsigned
 * 64-bit numbers → top 52 bits → (k + 0.5) / 2^52 ∈ (0, 1) → Box–Muller. Identical inputs give
 * bit-identical outputs on Android and iOS (golden-tested).
 */
public enum LocationNoise {
    /** The choices of the setting «Standort verschieben», in metres. */
    public static let SIGMAS_M: [Int] = [25, 50, 100, 200]
    public static let DEFAULT_SIGMA_M = 50
    /** Length of the per-device noise secret. */
    public static let SECRET_BYTES = 32

    /** The LV95 box the server accepts for points (inclusive, whole metres). */
    public static let E_MIN = 2_480_000
    public static let E_MAX = 2_839_999
    public static let N_MIN = 1_070_000
    public static let N_MAX = 1_299_999

    /** The point that is sent: LV95 whole metres, its σ, and whether it was pulled back into the box. */
    public struct Point: Equatable, Hashable {
        public let e: Int
        public let n: Int
        public let sigmaM: Int
        public let clamped: Bool
        public init(e: Int, n: Int, sigmaM: Int, clamped: Bool = false) {
            self.e = e; self.n = n; self.sigmaM = sigmaM; self.clamped = clamped
        }
        /** The hectare that contains the shifted point: where the map shows the measurement. */
        public var cell: String { Lv95.lv95ToCell(Double(e), Double(n)) }
    }

    public enum Failure: Error, Equatable {
        case badSecret
        case badSigma
        case notFinite
    }

    public static func isSigma(_ sigmaM: Int) -> Bool { SIGMAS_M.contains(sigmaM) }

    public static func inBounds(_ e: Int, _ n: Int) -> Bool { e >= E_MIN && e <= E_MAX && n >= N_MIN && n <= N_MAX }

    /** A fresh per-device secret ([SECRET_BYTES] random bytes from the system CSPRNG). */
    public static func newSecret() -> [UInt8] {
        var g = SystemRandomNumberGenerator()
        return (0..<SECRET_BYTES).map { _ in UInt8.random(in: 0...255, using: &g) }
    }

    /** HMAC-SHA256(secret, "<trueCell>|<sigmaM>"): 32 bytes, the seed of one place's offset. */
    public static func seed(_ secret: [UInt8], _ trueCell: String, _ sigmaM: Int) throws -> [UInt8] {
        guard secret.count == SECRET_BYTES else { throw Failure.badSecret }
        guard isSigma(sigmaM) else { throw Failure.badSigma }
        let key = SymmetricKey(data: secret)
        let mac = HMAC<SHA256>.authenticationCode(for: Array("\(trueCell)|\(sigmaM)".utf8), using: key)
        return Array(mac)
    }

    /** Bytes [offset] … [offset]+7 as an unsigned 64-bit number → (0, 1), never 0 or 1. */
    static func unitOpen(_ bytes: [UInt8], _ offset: Int) -> Double {
        var k: UInt64 = 0
        for i in 0..<8 { k = (k << 8) | UInt64(bytes[offset + i]) }
        return (Double(k >> 12) + 0.5) / TWO_POW_52
    }

    /** Two independent standard normal draws (z_E, z_N) from the first 16 bytes of [seed] (Box–Muller). */
    public static func standardNormals(_ seed: [UInt8]) -> (Double, Double) {
        precondition(seed.count >= 16, "seed needs at least 16 bytes")
        let u1 = unitOpen(seed, 0)
        let u2 = unitOpen(seed, 8)
        let r = sqrt(-2.0 * log(u1))
        return (r * cos(2.0 * Double.pi * u2), r * sin(2.0 * Double.pi * u2))
    }

    /** The offset (dE, dN) in metres for a seed: σ times the two standard normals. */
    public static func offsetFromSeed(_ seed: [UInt8], _ sigmaM: Int) -> Lv95.En {
        let (zE, zN) = standardNormals(seed)
        return Lv95.En(Double(sigmaM) * zE, Double(sigmaM) * zN)
    }

    /** The offset (dE, dN) in metres of the place whose true hectare is [trueCell]. */
    public static func offset(_ secret: [UInt8], _ trueCell: String, _ sigmaM: Int) throws -> Lv95.En {
        offsetFromSeed(try seed(secret, trueCell, sigmaM), sigmaM)
    }

    /**
     * The point to send for the true LV95 point ([e], [n]): moved by the offset of its hectare,
     * rounded to whole metres, kept inside the server's box. The true point is not part of the result.
     */
    public static func shift(_ secret: [UInt8], _ e: Double, _ n: Double, _ sigmaM: Int) throws -> Point {
        guard e.isFinite && n.isFinite else { throw Failure.notFinite }
        let d = try offset(secret, Lv95.lv95ToCell(e, n), sigmaM)
        let se = JavaMath.roundToInt(e + d.e)
        let sn = JavaMath.roundToInt(n + d.n)
        let ce = Swift.min(Swift.max(se, E_MIN), E_MAX)
        let cn = Swift.min(Swift.max(sn, N_MIN), N_MAX)
        return Point(e: ce, n: cn, sigmaM: sigmaM, clamped: ce != se || cn != sn)
    }

    private static let TWO_POW_52 = 4503599627370496.0
}
