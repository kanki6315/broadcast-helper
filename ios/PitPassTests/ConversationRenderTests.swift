import XCTest
import SwiftUI
@testable import PitPass

/// Run explicitly for native visual review. Uses synthetic, account-isolated data.
@MainActor final class ConversationRenderTests: XCTestCase {
    func testConversationsTabRendering() async throws {
        let app = AppSession()
        let event = 987654
        let sheetJSON = """
        {"eventId":987654,"eventName":"Watkins Glen · Test fixture","year":2026,"seriesName":"IMSA","championshipLabel":"Teams","priorYearLabel":"Prior year","formRounds":[],"classes":[{"className":"GTD PRO","color":"#333333","entries":[{"entryId":987654,"carNumber":"012","teamName":"Test Team","manufacturerLogoInvert":false,"priorYearAuto":false,"isGuest":false,"drivers":[{"name":"Alex Smith","rating":"P","isTbd":false}],"form":{}}]}]}
        """
        await app.store.save("/api/events/\(event)/sheet", etag: nil, body: Data(sheetJSON.utf8))
        let book = ConversationBook(eventId: event)
        book.open(server: app.serverURL.absoluteString, owner: app.padOwner)
        for record in book.records { _ = book.remove(record.id) }
        let person = ConversationPerson(entryId: 987654, name: "Alex Smith", car: "012", team: "Test Team")
        var record = Conversation(person: person)
        record.session = "Qualifying"
        record.topic = "Balance since practice"
        record.takeaway = "Rear instability improved. Expects the car to be stronger over a long run."
        XCTAssertTrue(book.save(record))
        let wanted = Conversation(person: .init(entryId: 987655, name: "Jamie Jones", car: "36", team: "Test Team"), kind: .planned, topic: "Tyre warm-up")
        XCTAssertTrue(book.save(wanted))
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "conversation-render-tests"))
        let workspace = WorkspaceState(scope: "render", defaults: defaults)
        workspace.set("event.\(event).tab", "conversations")
        let fixture = try JSONDecoder().decode(Sheet.self, from: Data(sheetJSON.utf8))
        let root = NavigationStack { SheetView(eventId: event, initialSheet: fixture) }.environment(app).environment(workspace)
        let controller = UIHostingController(rootView: root.preferredColorScheme(.light))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            for record in book.records { _ = book.remove(record.id) }
            defaults.removePersistentDomain(forName: "conversation-render-tests")
        }
        for (name, style) in [("conversations-light", UIUserInterfaceStyle.light), ("conversations-dark", .dark)] {
            controller.rootView = root.preferredColorScheme(style == .dark ? .dark : .light)
            window.overrideUserInterfaceStyle = style
            try await Task.sleep(for: .seconds(1))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }
}
