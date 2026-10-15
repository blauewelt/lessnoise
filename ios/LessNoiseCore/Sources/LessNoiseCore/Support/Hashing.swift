import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/** SHA-256 as lowercase hex (the golden tests pin the replay WAVs with it). */
public func sha256Hex(_ bytes: [UInt8]) -> String {
    SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
}
