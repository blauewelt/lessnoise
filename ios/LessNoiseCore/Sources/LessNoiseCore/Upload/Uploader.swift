import Foundation

/** Read access to the local records the upload sends (iOS: the SQLite database; tests: in memory). */
public protocol UploadSource: AnyObject {
    /** Minutes with `startEpochMs > afterMs`, oldest first (ties in insertion order), at most [limit]. */
    func minutesAfter(_ afterMs: Int, _ limit: Int) async -> [MinuteRecord]
    /** Events with `startEpochMs > afterMs`, oldest first, at most [limit]. */
    func eventsAfter(_ afterMs: Int, _ limit: Int) async -> [NoiseEvent]
    /** All measurements (start, end, shifted point), to find the point of a record. */
    func measurements() async -> [MeasurementRow]
    /** Phone model, audio source and calibration for the site call. */
    func siteExtras() async -> SiteExtras
}

/** Storage of the upload state (iOS: UserDefaults + Keychain; tests: memory). Updates are atomic. */
public protocol UploadStore: AnyObject {
    var state: UploadSnapshot { get }
    @discardableResult func update(_ transform: (UploadSnapshot) -> UploadSnapshot) -> UploadSnapshot
}

/** Storage of the report queue. */
public protocol ReportStore: AnyObject {
    var state: ReportSnapshot { get }
    @discardableResult func update(_ transform: (ReportSnapshot) -> ReportSnapshot) -> ReportSnapshot
}

public final class MemoryUploadStore: UploadStore {
    private let lock = NSLock()
    private var value: UploadSnapshot
    public init(_ initial: UploadSnapshot = UploadSnapshot()) { value = initial }
    public var state: UploadSnapshot { lock.lock(); defer { lock.unlock() }; return value }
    @discardableResult public func update(_ transform: (UploadSnapshot) -> UploadSnapshot) -> UploadSnapshot {
        lock.lock(); defer { lock.unlock() }
        value = transform(value)
        return value
    }
}

public final class MemoryReportStore: ReportStore {
    private let lock = NSLock()
    private var value: ReportSnapshot
    public init(_ initial: ReportSnapshot = ReportSnapshot()) { value = initial }
    public var state: ReportSnapshot { lock.lock(); defer { lock.unlock() }; return value }
    @discardableResult public func update(_ transform: (ReportSnapshot) -> ReportSnapshot) -> ReportSnapshot {
        lock.lock(); defer { lock.unlock() }
        value = transform(value)
        return value
    }
}

/** A FIFO async mutex (Kotlin's `Mutex`): unlike an actor, the critical section may await without interleaving. */
public actor AsyncMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init() {}

    public func lock() async {
        if !locked { locked = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    public func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }

    public func withLock<T>(_ body: () async throws -> T) async rethrows -> T {
        await lock()
        do {
            let r = try await body()
            unlock()
            return r
        } catch {
            unlock()
            throw error
        }
    }
}

/**
 * The upload of server/DESIGN.md §4.6 (Uploader.kt, same order of requests, same markers, same
 * budget): register once, send the site when it changed, the waiting reports (≤ 50 per request),
 * then every minute and event newer than the last acknowledged `start`, in batches, each with the
 * shifted point of its measurement; records of a measurement without a point are skipped. The
 * markers move only after the server acknowledged a batch. Golden-tested against the Kotlin
 * uploader on a scripted scenario (the exact request sequence and bodies).
 */
public final class Uploader {
    public enum Outcome: Equatable {
        case disabled
        case authFailed
        case done(minutes: Int, events: Int, refused: Int, more: Bool, reports: Int)
        case retry(String, rateLimited: Bool)
        case failed(String)
    }

    public enum DeleteOutcome: Equatable {
        case deleted
        case unknownToServer
        case failed(String)
    }

    private let client: UploadClient
    private let store: UploadStore
    private let source: UploadSource
    private let reports: ReportStore
    private let appVersion: String
    private let appBuild: Int
    private let clock: () -> Int
    private let maxRequestsPerRun: Int
    private let minuteBatch: Int
    private let eventBatch: Int

    private let mutex = AsyncMutex()
    private let abortLock = NSLock()
    private var _abort = false
    private var abort: Bool {
        get { abortLock.lock(); defer { abortLock.unlock() }; return _abort }
        set { abortLock.lock(); _abort = newValue; abortLock.unlock() }
    }

    public init(
        client: UploadClient, store: UploadStore, source: UploadSource, reports: ReportStore, appVersion: String, appBuild: Int,
        clock: @escaping () -> Int = { Int(Date().timeIntervalSince1970 * 1000) },
        maxRequestsPerRun: Int = 24, minuteBatch: Int = UploadClient.MAX_MINUTES, eventBatch: Int = UploadClient.MAX_EVENTS
    ) {
        self.client = client; self.store = store; self.source = source; self.reports = reports
        self.appVersion = appVersion; self.appBuild = appBuild; self.clock = clock
        self.maxRequestsPerRun = maxRequestsPerRun; self.minuteBatch = minuteBatch; self.eventBatch = eventBatch
    }

    private func stopRequested() -> Bool { abort || !store.state.enabled }

    /** Requests one [run] sends at most. */
    public var requestBudgetPerRun: Int { maxRequestsPerRun }

    @discardableResult
    public func enable() -> Bool {
        let now = clock()
        store.update { UploadRules.enable($0, nowMs: now) }
        return true
    }

    public func disable() {
        store.update { UploadRules.disable($0) }
        reports.update { ReportQueue.dropPending($0) }
    }

    public func pointAttached(_ measurementStartMs: Int) async {
        await mutex.withLock {
            _ = store.update { UploadRules.pointAttached($0, measurementStartMs: measurementStartMs) }
        }
    }

    public func rewindAfterReevaluation() { store.update { UploadRules.rewindAfterReevaluation($0) } }

    public func effectiveFloorMs(_ s: UploadSnapshot? = nil) -> Int { UploadRules.effectiveFloorMs(s ?? store.state, nowMs: clock()) }

    public func minutesPendingAfterMs(_ s: UploadSnapshot? = nil) -> Int { UploadRules.minutesPendingAfterMs(s ?? store.state, nowMs: clock()) }

    /** One upload run; [withMinutes] false: the events-only run of the live map. */
    public func run(withMinutes: Bool = true) async -> Outcome {
        await mutex.withLock { await runLocked(withMinutes) }
    }

    private func runLocked(_ withMinutes: Bool) async -> Outcome {
        var s = store.state
        if !s.enabled { return .disabled }
        if s.authFailed { return .authFailed }
        var requests = 0
        var sentReports = 0
        var sentMinutes = 0
        var sentEvents = 0
        var refused = 0
        do {
            if !s.registered {
                let reg = try await client.register(appVersion: appVersion, appBuild: appBuild)
                requests += 1
                s = store.update { var n = $0; n.deviceId = reg.deviceId; n.token = reg.token; n.lastSiteJson = nil; n.authFailed = false; return n }
            }
            let id = s.deviceId!
            let token = s.token!

            let places = PlaceIndex(await source.measurements())
            if let latestPoint = places.latest {
                let siteJson = Payloads.site(latestPoint, s.site, await source.siteExtras())
                let siteText = siteJson.description
                if siteText != s.lastSiteJson {
                    if stopRequested() { return .disabled }
                    try await client.putSite(id, token, siteJson)
                    requests += 1
                    s = store.update { var n = $0; n.lastSiteJson = siteText; return n }
                }
            }

            // Reports first: few, small, and their coordinates should not stay on the phone longer than needed.
            while true {
                if stopRequested() { return .disabled }
                let batch = Array(reports.state.pending.prefix(UploadClient.MAX_REPORTS))
                if batch.isEmpty || requests >= maxRequestsPerRun { break }
                let res: UploadResult
                do {
                    res = try await client.postReports(id, token, batch.map(Payloads.report))
                } catch let UploadFailure.rejected(status, _) {
                    requests += 1
                    // 404/405: a server without the reports call yet; keep them and go on.
                    if status == 404 || status == 405 { break }
                    // Any other 4xx: drop this batch (and its coordinates) instead of blocking every later upload.
                    store.update { var n = $0; n.refusedTotal += batch.count; return n }
                    reports.update { ReportQueue.acknowledge($0, batch, rejected: Set(batch.indices)) }
                    continue
                }
                requests += 1
                sentReports += res.accepted + res.duplicates
                if !res.rejected.isEmpty { store.update { var n = $0; n.refusedTotal += res.rejected.count; return n } }
                reports.update { ReportQueue.acknowledge($0, batch, rejected: Set(res.rejected.map { $0.index })) }
            }

            var more = false
            if withMinutes {
                guard let m = try await sendAll(
                    kind: RefusedRecord.MINUTE, batchSize: minuteBatch, budget: { self.maxRequestsPerRun - requests },
                    marker: { $0.minutesSentUpToMs },
                    fetch: { await self.source.minutesAfter($0, $1) }, start: { $0.startEpochMs },
                    include: { places.pointAt($0.startEpochMs) != nil },
                    post: { batch in try await self.client.postMinutes(id, token, batch.map { Payloads.minute($0, places.pointAt($0.startEpochMs)!) }) },
                    advance: { snap, t in var n = snap; n.minutesSentUpToMs = Swift.max(snap.minutesSentUpToMs, t); return n }
                ) else { return .disabled }
                requests += m.requests; sentMinutes = m.sent; refused += m.refused; more = m.more
            }
            if !more {
                guard let e = try await sendAll(
                    kind: RefusedRecord.EVENT, batchSize: eventBatch, budget: { self.maxRequestsPerRun - requests },
                    marker: { $0.eventsSentUpToMs },
                    fetch: { await self.source.eventsAfter($0, $1) }, start: { $0.startEpochMs },
                    include: { Payloads.isShared($0) && places.pointAt($0.startEpochMs) != nil },
                    post: { batch in try await self.client.postEvents(id, token, batch.map { Payloads.event($0, places.pointAt($0.startEpochMs)!) }) },
                    advance: { snap, t in var n = snap; n.eventsSentUpToMs = Swift.max(snap.eventsSentUpToMs, t); return n }
                ) else { return .disabled }
                requests += e.requests; sentEvents = e.sent; refused += e.refused; more = e.more
            }
            let now = clock()
            let sentSomething = requests > 0
            store.update { var n = $0; if sentSomething { n.lastSuccessAtMs = now }; n.lastError = nil; return n }
            return .done(minutes: sentMinutes, events: sentEvents, refused: refused, more: more, reports: sentReports)
        } catch let f as UploadFailure {
            switch f {
            case .unauthorized:
                store.update { var n = $0; n.authFailed = true; n.lastError = UploadRules.AUTH_FAILED_TEXT; return n }
                return .authFailed
            case let .rateLimited(_, message):
                store.update { var n = $0; n.lastError = UploadRules.RETRY_TEXT; return n }
                return .retry(message, rateLimited: true)
            case let .rejected(status, message):
                store.update { var n = $0; n.lastError = "Vom Server abgelehnt (\(status))."; return n }
                return .failed(message)
            case let .server(_, message):
                store.update { var n = $0; n.lastError = UploadRules.RETRY_TEXT; return n }
                return .retry(message, rateLimited: false)
            case let .noConnection(message):
                store.update { var n = $0; n.lastError = UploadRules.NO_CONNECTION_TEXT; return n }
                return .retry(message, rateLimited: false)
            }
        } catch {
            store.update { var n = $0; n.lastError = UploadRules.NO_CONNECTION_TEXT; return n }
            return .retry(String(describing: error), rateLimited: false)
        }
    }

    private struct Sent { let requests: Int; let sent: Int; let refused: Int; let more: Bool }

    /** Sends every record newer than the marker, batch by batch; nil if sharing was switched off in between. */
    private func sendAll<T>(
        kind: String, batchSize: Int, budget: () -> Int, marker: (UploadSnapshot) -> Int,
        fetch: (Int, Int) async -> [T], start: (T) -> Int, include: (T) -> Bool,
        post: ([T]) async throws -> UploadResult, advance: @escaping (UploadSnapshot, Int) -> UploadSnapshot
    ) async throws -> Sent? {
        var requests = 0
        var sent = 0
        var refusedCount = 0
        while true {
            if stopRequested() { return nil }
            let s = store.state
            if requests >= budget() { return Sent(requests: requests, sent: sent, refused: refusedCount, more: true) }
            // One row more than a batch, to see whether the batch would split records with one start.
            let raw = await fetch(Swift.max(marker(s), effectiveFloorMs(s) - 1), batchSize + 1)
            if raw.isEmpty { return Sent(requests: requests, sent: sent, refused: refusedCount, more: false) }
            let (rows, full) = UploadRules.batchRows(raw, batchSize: batchSize, start: start)
            let skip = Set(s.refused.filter { $0.kind == kind }.map { $0.startEpochMs })
            let batch = rows.filter { !skip.contains(start($0)) && include($0) }
            if !batch.isEmpty {
                let res = try await post(batch)
                requests += 1
                sent += res.accepted + res.duplicates
                refusedCount += res.rejected.count
                let starts = batch.map(start)
                store.update { UploadRules.recordRefused($0, kind: kind, starts: starts, rejected: res.rejected) }
            }
            let last = start(rows.last!)
            store.update { advance($0, last) }
            if !full { return Sent(requests: requests, sent: sent, refused: refusedCount, more: false) }
        }
    }

    /** «Vom Server löschen»: DELETE and wait for 204, then forget id and token, switch off, drop pending reports. */
    public func deleteServerData() async -> DeleteOutcome {
        abort = true
        return await mutex.withLock {
            abort = false
            let r = await deleteAndForget(disable: true)
            if case .failed = r {} else { reports.update { ReportQueue.dropPending($0) } }
            return r
        }
    }

    /** «Neu starten»: delete the server data like above, then start over with a fresh id (on the next upload). */
    public func newIdentity() async -> DeleteOutcome {
        abort = true
        return await mutex.withLock {
            abort = false
            return await deleteAndForget(disable: false)
        }
    }

    private func deleteAndForget(disable: Bool) async -> DeleteOutcome {
        let r = await deleteLocked()
        if case .failed = r { return r }
        let now = clock()
        store.update {
            var n = UploadRules.forget($0, nowMs: now)
            if disable { n.enabled = false; n.shareDecision = .NO }
            return n
        }
        return r
    }

    private func deleteLocked() async -> DeleteOutcome {
        let s = store.state
        if !s.registered { return .deleted }
        do {
            try await client.deleteDevice(s.deviceId!, s.token!)
            return .deleted
        } catch UploadFailure.unauthorized {
            return .unknownToServer
        } catch let f as UploadFailure {
            switch f {
            case let .rateLimited(_, m), let .rejected(_, m), let .server(_, m): return .failed(m)
            case .noConnection: return .failed("Keine Verbindung zum Server.")
            case .unauthorized: return .unknownToServer
            }
        } catch {
            return .failed("Keine Verbindung zum Server.")
        }
    }
}
