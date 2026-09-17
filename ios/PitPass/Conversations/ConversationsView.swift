import SwiftUI
import AVFoundation
import PencilKit

struct ConversationsView: View {
    @Environment(AppSession.self) private var appSession
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var typeSize
    @Bindable var book: ConversationBook
    @Bindable var audio: ConversationAudio
    let sheet: Sheet
    @Binding var selectedPerson: ConversationPerson?
    @State private var adding: Conversation.Kind?
    @State private var recordingChoice = false
    @State private var detail: Conversation?
    @State private var pendingDetail: Conversation?
    @State private var onlySession = false

    private var actionLayout: AnyLayout {
        sizeClass == .compact || typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(spacing: 10))
    }
    private var visibleRecords: [Conversation] {
        book.records.filter { !onlySession || ConversationSessions.matches($0.session, book.workingSession) }
    }
    private var people: [ConversationPerson] {
        book.people.filter { person in visibleRecords.contains { $0.person?.id == person.id } }
    }

    var body: some View {
        List {
            Section {
                ConversationContext(session: $book.workingSession, context: $book.workingContext, options: book.sessionOptions)
                actionLayout {
                    Button("Record conversation", systemImage: "mic") { recordingChoice = true; adding = .spoken }
                        .buttonStyle(.borderedProminent).tint(PP.accent).foregroundStyle(PP.onAccent)
                    Button("Add note", systemImage: "square.and.pencil") { recordingChoice = false; adding = .spoken }
                        .buttonStyle(.bordered)
                    Button("Want to speak to", systemImage: "person.badge.plus") { recordingChoice = false; adding = .planned }
                        .buttonStyle(.bordered)
                }
                .disabled(!book.writable || audio.recordingId != nil || audio.preparing)
                if let id = audio.recordingId {
                    Button { detail = book.record(id) } label: {
                        Label("Recording · \(conversationTime(audio.elapsed)) — open to stop", systemImage: "record.circle")
                            .foregroundStyle(PP.error)
                    }
                }
            }
            Section {
                Toggle("Only \(book.workingSession)", isOn: $onlySession)
                ForEach(people) { person in
                    Button { selectedPerson = person } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            HStack {
                                Text("#\(person.car)").monospacedDigit()
                                Text(person.name).fontWeight(.semibold)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.secondary)
                            }
                            let entries = visibleRecords.filter { $0.person?.id == person.id }
                            let spoken = entries.filter { $0.kind == .spoken }
                            Text(spoken.isEmpty ? "Want to speak to" : "\(spoken.count) conversation\(spoken.count == 1 ? "" : "s")")
                                .font(.caption).foregroundStyle(PP.accentInk)
                            if let latest = spoken.sorted(by: { $0.date > $1.date }).first {
                                Text(latest.takeaway.isEmpty ? latest.topic : latest.takeaway).lineLimit(2).foregroundStyle(.secondary)
                                Text("\(latest.session) · \(latest.context)").font(.caption).foregroundStyle(.secondary)
                            } else if let planned = entries.first { Text(planned.topic).foregroundStyle(.secondary) }
                        }.padding(.vertical, 5)
                    }.buttonStyle(.plain)
                    .modifier(ConversationSwipeDelete(book: book, audio: audio,
                        ids: Set(book.entries(for: person).map(\.id)), personName: person.name))
                }
                if people.isEmpty && visibleRecords.isEmpty {
                    ContentUnavailableView("No conversations yet", systemImage: "bubble.left.and.bubble.right",
                        description: Text("Record a conversation, add a note, or choose someone you want to speak to."))
                }
            } header: { Text("This weekend") }
            let unassigned = visibleRecords.filter { $0.person == nil }
            if !unassigned.isEmpty {
                Section("Assign a driver") {
                    ForEach(unassigned) { record in
                        Button { detail = record } label: { ConversationRow(record: record) }.buttonStyle(.plain)
                            .modifier(ConversationSwipeDelete(book: book, audio: audio, ids: [record.id]))
                    }
                }
            }
            Section {
                HStack {
                    Text(audio.assetStatus).font(.footnote)
                    Spacer()
                    if audio.preparingAssets { ProgressView() }
                    else if !audio.englishReady {
                        Button("Prepare English") { Task { await audio.checkAssets(download: true) } }
                    }
                }
                Text("Saved on this iPad for your account. Audio and notes are not uploaded or shared.")
                    .font(.footnote).foregroundStyle(.secondary)
                if let error = book.error { Text(error).foregroundStyle(PP.error) }
                if let error = audio.error { Text(error).foregroundStyle(PP.error) }
            }
        }
        .buttonStyle(.borderless)
        .scrollContentBackground(.hidden)
        .background(PP.bg)
        .task { await audio.checkAssets() }
        .task(id: sheet.eventId) {
            let resource = Resource<EventResults>("/api/events/\(sheet.eventId)/results")
            await resource.load(appSession.loader)
            resource.applyPendingUpdate()
            book.eventSessionNames = resource.value?.sessions.map(\.name) ?? []
        }
        .sheet(item: $adding, onDismiss: {
            detail = pendingDetail
            pendingDetail = nil
        }) { kind in
            ConversationChooser(book: book, audio: audio, roster: ConversationPerson.roster(sheet), kind: kind,
                                recordAudio: recordingChoice) { record in
                pendingDetail = record
                adding = nil
            }
        }
        .sheet(item: $selectedPerson) { person in
            NavigationStack {
                ConversationPersonView(book: book, audio: audio, person: person, roster: ConversationPerson.roster(sheet))
            }
        }
        .sheet(item: $detail) { record in
            NavigationStack {
                ConversationDetail(book: book, audio: audio, id: record.id, roster: ConversationPerson.roster(sheet))
            }
            .interactiveDismissDisabled(audio.recordingId != nil)
        }
    }
}

extension Conversation.Kind: Identifiable { var id: String { rawValue } }

private struct ConversationContext: View {
    @Binding var session: String
    @Binding var context: String
    let options: [String]
    var body: some View {
        Picker("Session", selection: Binding(
            get: { ConversationSessions.selection(session, in: options) },
            set: { session = $0 }
        )) {
            ForEach(options, id: \.self) { Text($0).tag($0) }
        }
        .pickerStyle(.menu)
        Picker("Broadcast context", selection: $context) {
            Text("Before going live").tag("Before going live")
            Text("On air").tag("On air")
        }
    }
}

private struct ConversationChooser: View {
    @Environment(\.dismiss) private var dismiss
    let book: ConversationBook
    let audio: ConversationAudio
    let roster: [ConversationPerson]
    let kind: Conversation.Kind
    let recordAudio: Bool
    let chosen: (Conversation) -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if recordAudio { Button("Record now · Assign driver later", systemImage: "mic") { choose(nil) } }
                ForEach(roster.filter { query.isEmpty || "\($0.name) \($0.car) \($0.team)".localizedCaseInsensitiveContains(query) }) { person in
                    Button { choose(person) } label: {
                        VStack(alignment: .leading) {
                            Text(person.name)
                            Text("#\(person.car) · \(person.team)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if let error = audio.error ?? book.error { Text(error).foregroundStyle(PP.error) }
            }
            .disabled(audio.preparing)
            .searchable(text: $query, prompt: "Driver, car or team")
            .navigationTitle(kind == .planned ? "Want to speak to" : "Who are you talking to?")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(audio.preparing) } }
        }
        .interactiveDismissDisabled(audio.preparing)
    }

    private func choose(_ person: ConversationPerson?) {
        var record = Conversation(person: person, kind: kind)
        record.session = book.workingSession.trimmingCharacters(in: .whitespacesAndNewlines)
        if record.session.isEmpty { record.session = "General weekend" }
        record.context = book.workingContext
        if recordAudio {
            Task { if let id = await audio.start(record, in: book), let saved = book.record(id) { chosen(saved) } }
        } else if book.save(record) { chosen(record) }
    }
}

private struct ConversationPersonView: View {
    @Environment(\.dismiss) private var dismiss
    let book: ConversationBook
    let audio: ConversationAudio
    let person: ConversationPerson
    let roster: [ConversationPerson]
    @State private var path: [UUID] = []
    var body: some View {
        List {
            Section {
                Button("Record conversation", systemImage: "mic") {
                    var record = Conversation(person: person)
                    record.session = book.workingSession
                    record.context = book.workingContext
                    Task { if let id = await audio.start(record, in: book) { path.append(id) } }
                }.disabled(audio.recordingId != nil || audio.preparing)
                Button("Add note", systemImage: "square.and.pencil") { add(.spoken) }
                Button("Want to speak to", systemImage: "person.badge.plus") { add(.planned) }
            } header: { Text("#\(person.car) · \(person.team)") }
            ForEach(book.entries(for: person)) { record in
                Button { path.append(record.id) } label: { ConversationRow(record: record) }.buttonStyle(.plain)
                    .modifier(ConversationSwipeDelete(book: book, audio: audio, ids: [record.id]))
            }
            if let error = audio.error ?? book.error { Text(error).foregroundStyle(PP.error) }
        }
        .navigationTitle(person.name)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(audio.recordingId != nil) } }
        .navigationDestination(isPresented: Binding(get: { !path.isEmpty }, set: { if !$0 { path = [] } })) {
            if let id = path.last { ConversationDetail(book: book, audio: audio, id: id, roster: roster) }
        }
        .interactiveDismissDisabled(audio.recordingId != nil)
    }
    private func add(_ kind: Conversation.Kind) {
        var record = Conversation(person: person, kind: kind)
        record.session = book.workingSession
        record.context = book.workingContext
        if book.save(record) { path.append(record.id) }
    }
}

private struct ConversationRow: View {
    let record: Conversation
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(record.kind == .planned ? "Want to speak to" : record.session).fontWeight(.semibold)
                Spacer()
                Text(record.date, style: .time).font(.caption).foregroundStyle(.secondary)
            }
            Text(record.context).font(.caption).foregroundStyle(.secondary)
            if !record.takeaway.isEmpty { Text(record.takeaway).lineLimit(3) }
            else if !record.topic.isEmpty { Text(record.topic).lineLimit(2) }
            if record.audioFile != nil {
                Label(record.recording ? "Recording" : "Audio · \(conversationTime(record.duration))", systemImage: "waveform")
                    .font(.caption).foregroundStyle(PP.accentInk)
            }
        }.padding(.vertical, 5)
    }
}

private struct ConversationDetail: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let book: ConversationBook
    let audio: ConversationAudio
    let id: UUID
    let roster: [ConversationPerson]
    @State private var player = ConversationPlayer()
    @State private var deleting = false
    @State private var saveError: String?
    @State private var erasingInk = false

    private func field<T>(_ keyPath: WritableKeyPath<Conversation, T>, fallback: T) -> Binding<T> {
        Binding(get: { book.record(id)?[keyPath: keyPath] ?? fallback }, set: { value in
            guard var record = book.record(id) else { return }
            record[keyPath: keyPath] = value
            if !book.save(record) { saveError = book.error }
        })
    }

    var body: some View {
        Group {
            if let record = book.record(id) {
                Form {
                    Section {
                        Picker("Driver", selection: field(\.person, fallback: nil)) {
                            Text("Unassigned").tag(nil as ConversationPerson?)
                            ForEach(Array(Set(roster + [record.person].compactMap { $0 })).sorted { $0.name < $1.name }) { person in
                                Text("#\(person.car) · \(person.name)").tag(Optional(person))
                            }
                        }
                        ConversationContext(session: field(\.session, fallback: "General weekend"), context: field(\.context, fallback: "Before going live"), options: book.sessionOptions)
                        DatePicker("When", selection: field(\.date, fallback: Date()))
                        if record.kind == .planned {
                            Button("Record this conversation", systemImage: "mic") {
                                player.stop()
                                Task { _ = await audio.start(record, in: book) }
                            }.disabled(audio.recordingId != nil || audio.preparing)
                            Button("Mark as spoken to", systemImage: "checkmark.bubble") {
                                var updated = record; updated.kind = .spoken; updated.date = Date(); _ = book.save(updated)
                            }
                        }
                    }
                    if audio.recordingId == id {
                        Section {
                            Label("Recording · \(conversationTime(audio.elapsed))", systemImage: "record.circle")
                                .font(.title2.monospacedDigit()).foregroundStyle(PP.error)
                            HStack {
                                Button("Mark moment", systemImage: "bookmark") { audio.mark() }
                                Spacer()
                                Button("Stop & save", systemImage: "stop.fill") { audio.stop() }.tint(PP.error)
                            }
                            Text("Audio saves on this iPad. English transcription follows when you stop.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Section("Asked / discussed") { TextField("Topic or question (optional)", text: field(\.topic, fallback: ""), axis: .vertical) }
                    Section("What they said") {
                        TextEditor(text: field(\.takeaway, fallback: "")).frame(minHeight: 110)
                        Text("Your takeaway or paraphrase. Notes save as you write.").font(.caption).foregroundStyle(.secondary)
                    }
                    Section("Handwritten notes") {
                        Toggle("Eraser", isOn: $erasingInk)
                        ConversationInk(data: field(\.ink, fallback: nil), erasing: erasingInk).frame(height: 230)
                    }
                    if let file = record.audioFile, let url = book.audioURL(file), audio.recordingId != id {
                        Section("Recording") {
                            HStack {
                                Button(player.playing ? "Pause" : "Play", systemImage: player.playing ? "pause.fill" : "play.fill") { player.toggle(url) }
                                Slider(value: Binding(get: { player.position }, set: { player.seek($0, url: url) }), in: 0...max(record.duration, player.duration, 1))
                                    .accessibilityLabel("Audio position")
                                Text(conversationTime(player.position)).monospacedDigit()
                                ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.accessibilityLabel("Share recording")
                            }
                            if let error = player.error { Text(error).foregroundStyle(PP.error) }
                            ForEach(Array(record.bookmarks.enumerated()), id: \.offset) { _, seconds in
                                Button("Marked moment · \(conversationTime(seconds))", systemImage: "bookmark") { player.seek(seconds, url: url) }
                            }
                        }
                        .disabled(audio.recordingId != nil)
                        Section("English transcript") {
                            if audio.transcribing == id { ProgressView("Transcribing on this iPad…") }
                            else {
                                Button(record.transcript.isEmpty ? "Transcribe recording" : "Transcribe again") {
                                    Task { await audio.transcribe(id, in: book) }
                                }.disabled(audio.transcribing != nil)
                            }
                            if let error = record.transcriptionError { Text(error).font(.footnote).foregroundStyle(.secondary) }
                            ForEach(record.transcript) { passage in
                                VStack(alignment: .leading, spacing: 8) {
                                    Button(conversationTime(passage.seconds)) { player.seek(passage.seconds, url: url) }
                                        .monospacedDigit().disabled(audio.recordingId != nil)
                                    Text(passage.text).textSelection(.enabled)
                                    Button("Add to takeaway") {
                                        var updated = record
                                        updated.takeaway += (updated.takeaway.isEmpty ? "" : "\n") + passage.text
                                        _ = book.save(updated)
                                    }.font(.caption)
                                }.padding(.vertical, 4)
                            }
                        }
                    }
                    if let error = saveError ?? book.error ?? audio.error { Section { Text(error).foregroundStyle(PP.error) } }
                    Section {
                        Button("Delete conversation", role: .destructive) { deleting = true }.disabled(audio.recordingId != nil || audio.transcribing == id)
                    } footer: { Text("Saved on this iPad · Not shared with the broadcast team") }
                }
                .buttonStyle(.borderless)
                .navigationTitle(record.person?.name ?? "Conversation")
            } else { ContentUnavailableView("Conversation unavailable", systemImage: "bubble.left") }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(audio.recordingId == id)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(audio.recordingId != nil) } }
        .confirmationDialog("Delete this conversation and its audio?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { player.stop(); if book.remove(id) { dismiss() } }
        }
        .onDisappear { player.stop() }
        .onChange(of: scenePhase) { _, phase in if phase == .background { audio.stop() } }
    }
}

@MainActor @Observable private final class ConversationPlayer {
    var playing = false
    var position: Double = 0
    var duration: Double = 0
    var error: String?
    private var player: AVAudioPlayer?
    private var timer: Timer?
    private func load(_ url: URL) -> Bool {
        if player != nil { return true }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            player = try AVAudioPlayer(contentsOf: url)
            duration = player?.duration ?? 0
            return true
        } catch { self.error = "Could not play audio: \(error.localizedDescription)"; return false }
    }
    func toggle(_ url: URL) {
        guard load(url), let player else { return }
        if playing { player.pause(); playing = false; timer?.invalidate() }
        else {
            if player.currentTime >= player.duration { player.currentTime = 0 }
            playing = player.play()
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    position = self.player?.currentTime ?? 0
                    playing = self.player?.isPlaying ?? false
                    if !playing { timer?.invalidate() }
                }
            }
        }
    }
    func seek(_ value: Double, url: URL) {
        guard load(url) else { return }
        player?.currentTime = min(max(0, value), duration); position = player?.currentTime ?? 0
    }
    func stop() {
        let ownedSession = player != nil
        player?.stop(); player = nil; timer?.invalidate(); timer = nil; playing = false
        guard ownedSession else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

private struct ConversationInk: UIViewRepresentable {
    @Binding var data: Data?
    var erasing: Bool
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> PKCanvasView {
        let view = PKCanvasView()
        view.backgroundColor = .white
        view.drawingPolicy = .anyInput
        view.tool = PKInkingTool(.pen, color: .black, width: 2)
        if let data, let drawing = try? PKDrawing(data: data) { view.drawing = drawing }
        view.delegate = context.coordinator
        return view
    }
    func updateUIView(_ view: PKCanvasView, context: Context) {
        context.coordinator.parent = self
        view.tool = erasing ? PKEraserTool(.vector) : PKInkingTool(.pen, color: .black, width: 2)
    }
    final class Coordinator: NSObject, PKCanvasViewDelegate {
        var parent: ConversationInk
        init(_ parent: ConversationInk) { self.parent = parent }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) { parent.data = canvasView.drawing.dataRepresentation() }
    }
}

func conversationTime(_ seconds: Double) -> String {
    let n = seconds.isFinite ? max(0, Int(seconds)) : 0
    return String(format: "%02d:%02d", n / 60, n % 60)
}

/// Native swipe affordance at every list level. Driver deletion always names its
/// full-weekend scope, even when the list is currently filtered to one session.
private struct ConversationSwipeDelete: ViewModifier {
    let book: ConversationBook
    let audio: ConversationAudio
    let ids: Set<UUID>
    var personName: String? = nil
    @State private var confirming = false

    private var busy: Bool {
        audio.preparing || ids.contains(where: { $0 == audio.recordingId || $0 == audio.transcribing })
    }
    func body(content: Content) -> some View {
        content
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                // A destructive swipe role removes the row optimistically.
                // Only the confirmation button below should perform deletion.
                Button { confirming = true } label: {
                    Label("Delete", systemImage: "trash")
                }
                .tint(.red)
                .buttonStyle(.automatic)
                .disabled(busy)
            }
            .confirmationDialog(personName.map { "Delete all conversations for \($0)?" }
                ?? "Delete this conversation?", isPresented: $confirming, titleVisibility: .visible) {
                Button(personName == nil ? "Delete conversation" : "Delete all \(ids.count) entries", role: .destructive) {
                    guard !busy else { return }
                    _ = book.remove(ids: ids)
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(personName == nil
                    ? "This removes the note, handwriting and any saved audio."
                    : "This removes all notes, planned contacts and audio for this driver across the entire event, including other sessions.")
            }
    }
}
