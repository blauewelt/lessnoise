import Foundation

/** CSV export (RFC 4180 quoting, '.' decimal separator, UTF-8): byte-identical to Csv.kt. */
public enum Csv {
    public static func field(_ s: String?) -> String {
        guard let s = s else { return "" }
        if s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == "\r\n" }) {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    public static func num(_ v: Double, _ decimals: Int = 1) -> String {
        (v.isNaN || v.isInfinite) ? "" : JavaFormat.fixed(v, decimals)
    }

    public static func minutes(_ records: [MinuteRecord], _ bucketIds: [String]) -> String {
        var sb = ""
        let header = [
            "start", "duration_s", "valid_s", "coverage", "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db", "l90_db",
            "event_count", "dominant_category",
        ] + bucketIds.map { "share_\($0)" } + [
            "classifier_frames", "calibration_id", "calibration_offset_db", "audio_source", "calibrated",
            "clock_corrections",
            "orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db",
            "recalibrated_from_id", "recalibration_offset_db",
            "local_floor_db", "wind_event_count",
        ]
        sb += header.joined(separator: ",") + "\n"
        for r in records {
            var row = [
                field(r.startIso), num(r.durationSeconds, 1), num(r.validSeconds, 1), num(r.coverage, 3), num(r.laeqDb), num(r.lafMaxDb), num(r.lafMinDb),
                num(r.l1Db), num(r.l10Db), num(r.l50Db), num(r.l90Db), String(r.eventCount),
                field(r.dominantCategory),
            ]
            row += bucketIds.map { id in r.categoryShares[id].map { num($0, 3) } ?? "" }
            row += [
                String(r.classifierFrames), r.calibrationId.map { String($0) } ?? "", num(r.calibrationOffsetDb, 2),
                field(r.audioSource), r.calibrated ? "true" : "false", String(r.clockCorrections),
            ]
            if let o = r.original {
                row += [num(o.laeqDb), num(o.lafMaxDb), num(o.lafMinDb), num(o.l1Db), num(o.l10Db), num(o.l50Db), num(o.l90Db)]
            } else {
                row += Array(repeating: "", count: 7)
            }
            row += [field(r.recalibratedFromId), r.recalibrationOffsetDb.map { num($0, 2) } ?? ""]
            row += [num(r.localFloorDb), String(r.windEventCount)]
            sb += row.joined(separator: ",") + "\n"
        }
        return sb
    }

    /** The v0.4.0 event feature columns (appended to the events CSV). */
    public static let EVENT_FEATURE_COLUMNS = [
        "local_floor_db", "excess_db", "rise_s", "decay_s", "jaggedness", "mid_band_rise_db", "lf_share", "lf_flutter_db",
        "wind", "shape",
    ]

    /** The feature columns of [e]; empty for events before v0.4.0 (wind = false). */
    public static func featureFields(_ e: NoiseEvent) -> [String] {
        guard let f = e.features else { return Array(repeating: "", count: EVENT_FEATURE_COLUMNS.count - 2) + ["false", ""] }
        return [
            num(f.localFloorDb), num(f.excessDb), num(f.riseS, 3), num(f.decayS, 3), num(f.jaggedness, 3),
            num(f.midBandRiseDb), num(f.lfShare, 3), num(f.lfFlutterDb, 2), f.wind ? "true" : "false", field(f.shape),
        ]
    }

    public static func events(_ events: [NoiseEvent]) -> String {
        var sb = ""
        sb += ([
            "start", "duration_s", "lafmax_db", "sel_db", "background_db", "threshold_db", "min_level_db",
            "dominant_category", "dominant_score",
            "label1", "score1", "label2", "score2", "label3", "score3",
            "classifier_frames", "calibration_id", "audio_source", "calibrated",
            "orig_lafmax_db", "orig_sel_db", "orig_background_db", "recalibrated_from_id", "recalibration_offset_db",
        ] + EVENT_FEATURE_COLUMNS).joined(separator: ",") + "\n"
        for e in events {
            var labels: [String] = []
            for i in 0..<3 {
                let l = i < e.topLabels.count ? e.topLabels[i] : nil
                labels += [field(l?.label), l.map { num(Double($0.score), 3) } ?? ""]
            }
            var row = [
                field(e.startIso), num(e.durationSeconds, 3), num(e.lafMaxDb), num(e.selDb), num(e.backgroundDb),
                num(e.thresholdDb), num(e.minLevelDb), field(e.dominantCategory), num(Double(e.dominantScore), 3),
            ]
            row += labels
            row += [
                String(e.classifierFrames), e.calibrationId.map { String($0) } ?? "", field(e.audioSource),
                e.calibrated ? "true" : "false",
            ]
            if let o = e.original { row += [num(o.lafMaxDb), num(o.selDb), num(o.backgroundDb)] } else { row += ["", "", ""] }
            row += [field(e.recalibratedFromId), e.recalibrationOffsetDb.map { num($0, 2) } ?? ""]
            row += featureFields(e)
            sb += row.joined(separator: ",") + "\n"
        }
        return sb
    }
}
