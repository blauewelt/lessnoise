import Foundation
import Testing
@testable import LessNoiseCore

/** The JVM number semantics the port relies on (checked against values printed by the JDK 21). */
@Suite struct JavaCompatTests {
    @Test func fixedFormatMatchesJava() {
        // (value, %.1f, %.2f, %.3f, %.0f) as printed by `String.format(Locale.ROOT, …)` on JDK 21.
        let cases: [(Double, String, String, String, String)] = [
            (0.15, "0.2", "0.15", "0.150", "0"),
            (0.125, "0.1", "0.13", "0.125", "0"),
            (7.25, "7.3", "7.25", "7.250", "7"),
            (2.5, "2.5", "2.50", "2.500", "3"),
            (0.05, "0.1", "0.05", "0.050", "0"),
            (1.005, "1.0", "1.01", "1.005", "1"),
            (112.35, "112.4", "112.35", "112.350", "112"),
            (-0.05, "-0.1", "-0.05", "-0.050", "-0"),
            (-0.04, "-0.0", "-0.04", "-0.040", "-0"),
            (0.0005, "0.0", "0.00", "0.001", "0"),
            (1e-5, "0.0", "0.00", "0.000", "0"),
            (1e7, "10000000.0", "10000000.00", "10000000.000", "10000000"),
            (123456789.0, "123456789.0", "123456789.00", "123456789.000", "123456789"),
            (0.001, "0.0", "0.00", "0.001", "0"),
            (0.0001, "0.0", "0.00", "0.000", "0"),
            (-0.0, "-0.0", "-0.00", "-0.000", "-0"),
            (0.35, "0.4", "0.35", "0.350", "0"),
            (1.45, "1.5", "1.45", "1.450", "1"),
            (2.675, "2.7", "2.68", "2.675", "3"),
            (69.25, "69.3", "69.25", "69.250", "69"),
            (41.85, "41.9", "41.85", "41.850", "42"),
            (0.9995, "1.0", "1.00", "1.000", "1"),
            (9.995, "10.0", "10.00", "9.995", "10"),
        ]
        for (v, f1, f2, f3, f0) in cases {
            #expect(JavaFormat.fixed(v, 1) == f1, "\(v) %.1f")
            #expect(JavaFormat.fixed(v, 2) == f2, "\(v) %.2f")
            #expect(JavaFormat.fixed(v, 3) == f3, "\(v) %.3f")
            #expect(JavaFormat.fixed(v, 0) == f0, "\(v) %.0f")
        }
    }

    @Test func doubleToStringMatchesJava() {
        let cases: [(Double, String)] = [
            (0.15, "0.15"), (60.0, "60.0"), (42.3, "42.3"), (0.001, "0.001"), (5e-4, "5.0E-4"), (1e-5, "1.0E-5"),
            (1e7, "1.0E7"), (123456789.0, "1.23456789E8"), (-0.0, "-0.0"), (0.0, "0.0"), (112.35, "112.35"),
            (9999999.0, "9999999.0"), (-3.5, "-3.5"), (47.37691, "47.37691"),
        ]
        for (v, s) in cases { #expect(JavaFormat.doubleToString(v) == s, "\(v)") }
    }

    @Test func javaRoundIsHalfUp() {
        #expect(JavaMath.round(2.5) == 3)
        #expect(JavaMath.round(-2.5) == -2)
        #expect(JavaMath.round(0.49999999999999994) == 0)
        #expect(JavaMath.round(-0.5) == 0)
        #expect(JavaMath.round(.nan) == 0)
        #expect(JavaMath.floorDiv(-1, 1000) == -1)
        #expect(JavaMath.floorMod(-1, 1000) == 999)
        #expect(JavaMath.toInt(-2.7) == -2)
    }
}
