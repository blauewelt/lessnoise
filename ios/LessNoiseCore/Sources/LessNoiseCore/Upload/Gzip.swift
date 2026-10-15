import Foundation
#if canImport(Compression)
import Compression
#endif

/**
 * gzip (RFC 1952) for the request bodies (`Content-Encoding: gzip`, like GZIPOutputStream on
 * Android). Header: no file name, mtime 0, OS 255 ("unknown"), which is what the JDK ≥ 16 writes.
 *
 * On Apple platforms the deflate stream comes from the Compression framework
 * (COMPRESSION_ZLIB = raw RFC 1951 deflate). Elsewhere (the Linux test runs) the data is wrapped
 * in stored deflate blocks: valid gzip, not compressed. The CRC-32 and the trailer are computed
 * here in both cases and golden-tested against java.util.zip.CRC32.
 */
public enum Gzip {
    public static func compress(_ data: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff]
        out += deflate(data)
        let crc = crc32(data)
        let size = UInt32(truncatingIfNeeded: data.count)
        for v in [crc, size] { for i in 0..<4 { out.append(UInt8((v >> (8 * UInt32(i))) & 0xff)) } }
        return out
    }

    /** Raw deflate of [data]. */
    static func deflate(_ data: [UInt8]) -> [UInt8] {
        #if canImport(Compression)
        if let d = appleDeflate(data) { return d }
        #endif
        return storedBlocks(data)
    }

    #if canImport(Compression)
    private static func appleDeflate(_ data: [UInt8]) -> [UInt8]? {
        if data.isEmpty { return nil }
        let capacity = data.count + data.count / 2 + 1024
        var dst = [UInt8](repeating: 0, count: capacity)
        let n = data.withUnsafeBufferPointer { src in
            dst.withUnsafeMutableBufferPointer { d in
                compression_encode_buffer(d.baseAddress!, capacity, src.baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        return n > 0 ? Array(dst.prefix(n)) : nil
    }
    #endif

    /** Deflate "stored" blocks (BTYPE 00), at most 65 535 bytes each. */
    static func storedBlocks(_ data: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        var pos = 0
        repeat {
            let len = Swift.min(65_535, data.count - pos)
            let final: UInt8 = pos + len >= data.count ? 1 : 0
            out.append(final) // BFINAL + BTYPE 00, byte-aligned
            out.append(UInt8(len & 0xff)); out.append(UInt8(len >> 8))
            let nlen = ~UInt16(len)
            out.append(UInt8(nlen & 0xff)); out.append(UInt8(nlen >> 8))
            out += data[pos..<(pos + len)]
            pos += len
        } while pos < data.count
        return out
    }

    private static let table: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    /** CRC-32 (IEEE 802.3), as java.util.zip.CRC32. */
    public static func crc32(_ data: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in data { c = table[Int((c ^ UInt32(b)) & 0xff)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}
