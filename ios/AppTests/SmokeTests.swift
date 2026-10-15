import XCTest
import LessNoiseKit
@testable import WenigerLaerm

final class SmokeTests: XCTestCase {
    func testKitLinksIntoTheApp() throws {
        XCTAssertEqual(AudioSource.IOS_MEASUREMENT, "IOS")
        XCTAssertNotNil(try AppDatabase.inMemory())
    }
}
