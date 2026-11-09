import Foundation

// Question lab: runs the app's own briefing + suggester code on a Mac against
// a Pit Pass server, so question quality can be judged on real drivers
// before touching the UI. See run.sh.
//
//   run.sh --event 9 --driver "Kevin Estre" --pdf storylines.pdf [--topic "…"]
//          [--api http://localhost:8731] [--on-device] [--prompt]

var args = CommandLine.arguments.dropFirst()
var options: [String: String] = [:]
var flags = Set<String>()
while let arg = args.popFirst() {
    if ["--on-device", "--prompt", "--blocks"].contains(arg) { flags.insert(arg) }
    else if arg.hasPrefix("--"), let value = args.popFirst() { options[arg] = value }
}
guard let eventId = options["--event"].flatMap(Int.init), let driverName = options["--driver"] else {
    print("usage: --event <id> --driver <name> [--pdf <path>] [--topic <text>] [--api <url>] [--on-device] [--prompt] [--blocks]")
    exit(2)
}
let api = options["--api"] ?? "http://localhost:8731"

func get<T: Decodable>(_ path: String, as: T.Type = T.self) async throws -> T {
    let (data, response) = try await URLSession.shared.data(from: URL(string: api + path)!)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else {
        throw NSError(domain: "lab", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(path): \(String(decoding: data.prefix(300), as: UTF8.self))"])
    }
    return try JSONDecoder().decode(T.self, from: data)
}

let sheet: Sheet = try await get("/api/events/\(eventId)/sheet")
let wanted = driverName.lowercased()
guard let entry = sheet.classes.flatMap(\.entries).first(where: { $0.drivers.contains { $0.name.lowercased() == wanted } }),
      let driver = entry.drivers.first(where: { $0.name.lowercased() == wanted }) else {
    print("\(driverName) isn't on the \(sheet.eventName) sheet"); exit(1)
}
let hits: [DriverSearchHit] = try await get(DriverLookup.searchPath(driver.name))
var profile: DriverProfile?, stats: DriverStats?
if let hit = DriverLookup.pick(hits, wanted: driver.name) {
    profile = try? await get("/api/drivers/\(hit.id)/profile")
    stats = try? await get("/api/drivers/\(hit.id)/stats")
}

var blocks: [String] = []
if let path = options["--pdf"] {
    let url = URL(fileURLWithPath: path)
    let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)?.timeIntervalSince1970 ?? 0
    let started = Date()
    blocks = try await StorylineText.blocks(pdf: Data(contentsOf: url), key: "lab:\(url.path):\(modified)")
    print("storylines: \(blocks.count) blocks, \(blocks.reduce(0) { $0 + $1.count }) chars (\(String(format: "%.1f", Date().timeIntervalSince(started)))s)")
    if flags.contains("--blocks") { for (i, b) in blocks.enumerated() { print("S\(i + 1): \(b)") }; exit(0) }
}

var planned: Conversation?
if let topic = options["--topic"] {
    planned = Conversation(kind: .planned, session: options["--session"] ?? "General weekend", topic: topic)
}
let briefing = BriefingBuilder.build(.init(sheet: sheet, entry: entry, driver: driver, profile: profile, stats: stats,
                                           storylineParagraphs: blocks, planned: planned))
print("\(briefing.facts.count) facts, \(briefing.relevantStorylines.count) relevant storylines of \(briefing.storylines.count)")

let started = Date()
let result = try await QuestionSuggester.suggest(briefing, allowPrivateCloud: !flags.contains("--on-device"))
if flags.contains("--prompt") { print("\n----- PROMPT (\(result.prompt.count) chars) -----\n\(result.prompt)\n-----") }
print("\nModel: \(result.model.rawValue)\(result.fallbackReason.map { " — \($0)" } ?? "") (\(String(format: "%.1f", Date().timeIntervalSince(started)))s, prompt \(result.prompt.count) chars)\n")
let lines = briefing.lines
for (i, q) in result.questions.enumerated() {
    print("\(i + 1). [\(q.angle)] \(q.question)")
    for id in q.sources { print("     \(id): \(lines[id].map { String($0.text.prefix(140)) } ?? "?")") }
    if q.sources.isEmpty { print("     (no valid source cited)") }
}
