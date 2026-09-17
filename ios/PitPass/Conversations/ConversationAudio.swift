import AVFoundation
import Speech
import Observation

/// The audio file is authoritative; transcription can always be retried offline.
@MainActor @Observable
final class ConversationAudio: NSObject, AVAudioRecorderDelegate {
    private(set) var recordingId: UUID?
    private(set) var preparing = false
    private(set) var transcribing: UUID?
    private(set) var elapsed: Double = 0
    private(set) var assetStatus = "English transcription not checked"
    private(set) var preparingAssets = false
    var error: String?
    private var recorder: AVAudioRecorder?
    private var book: ConversationBook?
    private var timer: Timer?
    private var interruption: NSObjectProtocol?
    private var pending: [(UUID, ConversationBook)] = []

    override init() {
        super.init()
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main) { [weak self] notification in
            let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if type == AVAudioSession.InterruptionType.began.rawValue {
                Task { @MainActor [weak self] in self?.stop() }
            }
        }
    }

    func checkAssets(download: Bool = false) async {
        guard !preparingAssets else { return }
        preparingAssets = true
        defer { preparingAssets = false }
        do {
            let transcriber = try await englishTranscriber()
            if download, let locale = transcriber.selectedLocales.first {
                _ = try await AssetInventory.reserve(locale: locale)
            }
            if download, let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                assetStatus = "Downloading English speech model…"
                try await request.downloadAndInstall()
            }
            let status = await AssetInventory.status(forModules: [transcriber])
            assetStatus = status == .installed ? "English ready · On device" : "Download English for offline transcription"
        } catch { assetStatus = error.localizedDescription }
    }

    func start(_ draft: Conversation, in book: ConversationBook) async -> UUID? {
        guard recordingId == nil, !preparing else { return nil }
        preparing = true
        defer { preparing = false }
        guard await AVAudioApplication.requestRecordPermission() else {
            error = "Microphone access is off. Enable it for Pit Pass in Settings to record a conversation."
            return nil
        }
        do {
            var record = draft
            record.kind = .spoken
            record.date = Date()
            record.audioFile = "\(record.id.uuidString).caf"
            record.recording = true
            guard let url = book.audioURL(record.audioFile!), book.save(record) else {
                error = book.error ?? "Conversation storage is unavailable."
                return nil
            }
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try audioSession.setActive(true)
            let recorder = try AVAudioRecorder(url: url, settings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 24000,
                AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false
            ])
            recorder.delegate = self
            guard recorder.record() else { throw CocoaError(.fileWriteUnknown) }
            self.recorder = recorder
            self.book = book
            recordingId = record.id
            elapsed = 0
            error = nil
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in self?.elapsed = self?.recorder?.currentTime ?? 0 }
            }
            return record.id
        } catch {
            if var record = book.record(draft.id) {
                record.recording = false
                record.transcriptionError = "Recording could not start: \(error.localizedDescription)"
                _ = book.save(record)
            }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            self.error = "Could not start recording: \(error.localizedDescription)"
            return nil
        }
    }

    func mark() {
        guard let id = recordingId, let book, var record = book.record(id) else { return }
        record.bookmarks.append(recorder?.currentTime ?? elapsed)
        _ = book.save(record)
    }

    func stop() {
        guard let id = recordingId, let book else { return }
        let duration = recorder?.currentTime ?? elapsed
        recordingId = nil
        recorder?.stop()
        recorder = nil
        timer?.invalidate()
        timer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        guard var record = book.record(id) else { return }
        record.recording = false
        record.duration = duration
        record.transcriptionError = "Audio saved. Waiting for English transcription."
        guard book.save(record) else { error = book.error; return }
        Task { await transcribe(id, in: book) }
    }

    func transcribe(_ id: UUID, in book: ConversationBook) async {
        if let active = transcribing {
            if active != id && !pending.contains(where: { $0.0 == id }) { pending.append((id, book)) }
            return
        }
        transcribing = id
        defer {
            transcribing = nil
            if !pending.isEmpty {
                let next = pending.removeFirst()
                Task { await transcribe(next.0, in: next.1) }
            }
        }
        guard let record = book.record(id), let file = record.audioFile,
              let url = book.audioURL(file) else { return }
        var analyzer: SpeechAnalyzer?
        do {
            let transcriber = try await englishTranscriber()
            guard await AssetInventory.status(forModules: [transcriber]) == .installed else {
                throw SpeechFailure("Audio saved. Download the English model, then retry transcription.")
            }
            let engine = SpeechAnalyzer(modules: [transcriber])
            analyzer = engine
            let results = Task { () throws -> [Conversation.Passage] in
                var passages: [Conversation.Passage] = []
                for try await result in transcriber.results {
                    passages.append(.init(seconds: result.range.start.seconds, text: String(result.text.characters)))
                }
                return passages
            }
            do {
                let file = try AVAudioFile(forReading: url)
                let duration = Double(file.length) / file.processingFormat.sampleRate
                _ = try await engine.analyzeSequence(from: file)
                try await engine.finalizeAndFinishThroughEndOfInput()
                let passages = try await results.value
                // Read latest copy: notes may have been edited during transcription.
                if var latest = book.record(id) {
                    latest.transcript = passages
                    latest.duration = duration
                    latest.transcriptionError = passages.isEmpty ? "No speech was recognized. The audio is still available." : nil
                    _ = book.save(latest)
                }
            } catch {
                await engine.cancelAndFinishNow()
                results.cancel()
                throw error
            }
        } catch {
            await analyzer?.cancelAndFinishNow()
            if var latest = book.record(id) {
                latest.transcriptionError = error.localizedDescription
                _ = book.save(latest)
            }
        }
    }

    private func englishTranscriber() async throws -> SpeechTranscriber {
        guard SpeechTranscriber.isAvailable,
              let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: "en-US")) else {
            throw SpeechFailure("English transcription is unavailable on this device. Audio recording and notes still work.")
        }
        return SpeechTranscriber(locale: locale, preset: .transcription)
    }

    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self, recordingId != nil else { return }
            if !flag { error = "Recording was interrupted. Check the saved audio." }
            stop()
        }
    }
    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: (any Error)?) {
        let message = error?.localizedDescription ?? "Audio encoding failed."
        Task { @MainActor [weak self] in self?.error = message; self?.stop() }
    }
}

private struct SpeechFailure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
