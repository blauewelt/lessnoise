import AVFoundation
import Foundation
import LessNoiseKit

/**
 * The microphone (ios/DESIGN.md §2): AVAudioSession category `.record`, mode `.measurement` (no
 * AGC, no noise suppression, no voice processing on the built-in microphones), preferred 48 kHz
 * and the built-in microphone; no Bluetooth (headsets deliver voice-processed 16/24 kHz audio).
 * An AVAudioEngine input tap delivers ≈ 100 ms blocks; the first channel is taken and, if the
 * hardware does not run at 48 kHz, converted with AVAudioConverter. Nothing is written anywhere:
 * each block is handed to the [AudioInputHandler] and dropped.
 *
 * Interruptions (call, Siri, alarm, another app taking the microphone) and engine configuration
 * changes (route change, sample rate change) are reported as interrupted; capture is restarted
 * when iOS allows it and then reported as resumed. The measurement's timeline is kept by the
 * session (MeasurementSession fills the gap as invalid time).
 */
final class AudioCapture: AudioInput {
    enum CaptureError: LocalizedError {
        case permission
        case noInput
        case session(String)
        var errorDescription: String? {
            switch self {
            case .permission: return MeasureTexts.NO_MIC_PERMISSION
            case .noInput: return "Kein Mikrofon verfügbar."
            case let .session(m): return "Mikrofon konnte nicht geöffnet werden: \(m)"
            }
        }
    }

    static let targetRate = Double(Acoustics.SAMPLE_RATE)

    private var engine = AVAudioEngine()
    private weak var handler: AudioInputHandler?
    private var converter: AVAudioConverter?
    private var converterInput: AVAudioFormat?
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioCapture.targetRate, channels: 1, interleaved: false)!
    private var observers: [NSObjectProtocol] = []
    private let lock = NSLock()
    private var running = false
    private var interrupted = false
    private var mono = [Float](repeating: 0, count: 0)

    /** The microphone permission (asked at the first «Starten»). */
    static var permission: AVAudioSession.RecordPermission { AVAudioSession.sharedInstance().recordPermission }

    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in DispatchQueue.main.async { done(granted) } }
    }

    func start(_ handler: AudioInputHandler) throws -> String {
        guard AudioCapture.permission == .granted else { throw CaptureError.permission }
        lock.lock(); defer { lock.unlock() }
        if running { stopLocked() }
        self.handler = handler
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.record, mode: .measurement, options: [])
            try? s.setPreferredSampleRate(AudioCapture.targetRate)
            try? s.setPreferredIOBufferDuration(0.02)
            try s.setActive(true, options: [])
            if let mic = s.availableInputs?.first(where: { $0.portType == .builtInMic }) { try? s.setPreferredInput(mic) }
        } catch {
            throw CaptureError.session(error.localizedDescription)
        }
        try startEngineLocked()
        running = true
        interrupted = false
        addObservers()
        return describe()
    }

    func stop() {
        lock.lock(); defer { lock.unlock() }
        stopLocked()
    }

    private func stopLocked() {
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        handler = nil
        converter = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func startEngineLocked() throws {
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        let input = engine.inputNode
        let hw = input.outputFormat(forBus: 0)
        guard hw.sampleRate > 0, hw.channelCount > 0 else { throw CaptureError.noInput }
        if hw.sampleRate == AudioCapture.targetRate {
            converter = nil
            converterInput = nil
        } else {
            // Some routes run at 44.1 kHz: resample the first channel to 48 kHz.
            let monoHw = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: hw.sampleRate, channels: 1, interleaved: false)!
            converter = AVAudioConverter(from: monoHw, to: target)
            converterInput = monoHw
        }
        let frames = AVAudioFrameCount(hw.sampleRate / 10) // ≈ 100 ms
        input.installTap(onBus: 0, bufferSize: frames, format: hw) { [weak self] buffer, _ in self?.deliver(buffer) }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw CaptureError.session(error.localizedDescription)
        }
    }

    /** The tap's thread: first channel → (48 kHz) → handler. */
    private func deliver(_ buffer: AVAudioPCMBuffer) {
        guard let h = handler, let ch = buffer.floatChannelData else { return }
        let n = Int(buffer.frameLength)
        if n == 0 { return }
        if let conv = converter, let inFormat = converterInput {
            guard let monoBuf = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: AVAudioFrameCount(n)) else { return }
            monoBuf.frameLength = AVAudioFrameCount(n)
            monoBuf.floatChannelData![0].update(from: ch[0], count: n)
            let outCap = AVAudioFrameCount(Double(n) * AudioCapture.targetRate / inFormat.sampleRate) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: outCap) else { return }
            var fed = false
            var error: NSError?
            conv.convert(to: out, error: &error) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return monoBuf
            }
            if error != nil || out.frameLength == 0 { return }
            h.audioBlock(UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))
        } else {
            h.audioBlock(UnsafeBufferPointer(start: ch[0], count: n))
        }
    }

    private func addObservers() {
        let nc = NotificationCenter.default
        let s = AVAudioSession.sharedInstance()
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: s, queue: nil) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            if type == .began { self?.interruptionBegan() } else { self?.resume() }
        })
        // A route or sample-rate change stops the engine: restart it with the new format.
        observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            self?.interruptionBegan()
            self?.resume()
        })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: s, queue: nil) { [weak self] _ in
            self?.mediaServicesReset()
        })
    }

    private func interruptionBegan() {
        lock.lock()
        let h = handler
        let first = running && !interrupted
        if first { interrupted = true }
        lock.unlock()
        if first { h?.audioInterrupted(true) }
    }

    private func resume() {
        lock.lock()
        guard running else { lock.unlock(); return }
        let h = handler
        do {
            try AVAudioSession.sharedInstance().setActive(true, options: [])
            try startEngineLocked()
            interrupted = false
            lock.unlock()
            h?.audioInterrupted(false)
        } catch {
            lock.unlock()
            // Not allowed (yet), e.g. the call goes on: try again in a few seconds, then give up.
            retryResume(attempt: 1)
        }
    }

    private func retryResume(attempt: Int) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            guard self.running, self.interrupted else { self.lock.unlock(); return }
            let h = self.handler
            do {
                try AVAudioSession.sharedInstance().setActive(true, options: [])
                try self.startEngineLocked()
                self.interrupted = false
                self.lock.unlock()
                h?.audioInterrupted(false)
            } catch {
                self.lock.unlock()
                if attempt < 24 { self.retryResume(attempt: attempt + 1) } else { h?.audioFailed(error.localizedDescription) }
            }
        }
    }

    private func mediaServicesReset() {
        lock.lock()
        guard running else { lock.unlock(); return }
        let h = handler
        engine = AVAudioEngine()
        lock.unlock()
        h?.audioInterrupted(true)
        lock.lock()
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.record, mode: .measurement, options: [])
            try s.setActive(true, options: [])
            try startEngineLocked()
            for o in observers { NotificationCenter.default.removeObserver(o) }
            observers = []
            addObservers()
            interrupted = false
            lock.unlock()
            h?.audioInterrupted(false)
        } catch {
            lock.unlock()
            h?.audioFailed(error.localizedDescription)
        }
    }

    /** «Eingebautes Mikrofon · 48000 Hz · Messmodus» (diagnostics). */
    private func describe() -> String {
        let s = AVAudioSession.sharedInstance()
        let port = s.currentRoute.inputs.first?.portName ?? "?"
        let rate = Int(engine.inputNode.outputFormat(forBus: 0).sampleRate)
        return "\(port) · \(rate) Hz · \(s.mode == .measurement ? "Messmodus" : s.mode.rawValue)" + (converter != nil ? " → 48000 Hz" : "")
    }
}
