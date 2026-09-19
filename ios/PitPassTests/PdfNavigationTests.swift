import PDFKit
import XCTest
@testable import PitPass

@MainActor
final class PdfNavigationTests: XCTestCase {
    private func document() throws -> PDFDocument {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for number in 1...8 {
                context.beginPage()
                ("Team \(number)" as NSString).draw(at: CGPoint(x: 30, y: 30), withAttributes: nil)
            }
        }
        return try XCTUnwrap(PDFDocument(data: data))
    }

    func testDeferredNavigationAndUpdatedTargetsPreserveManualReading() async throws {
        let document = try document()
        let controller = PdfViewController()
        controller.show(document: document, page: 6)
        // An early layout without a window must not consume the deep link.
        controller.viewDidLayoutSubviews()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 834, height: 1100)
        let presenter = UIViewController()
        window.rootViewController = presenter
        window.makeKeyAndVisible()
        presenter.present(controller, animated: false)
        try await Task.sleep(for: .milliseconds(300))
        defer { window.isHidden = true }
        window.layoutIfNeeded()
        controller.view.layoutIfNeeded()
        XCTAssertTrue(controller.pdfView.currentPage === document.page(at: 5))

        controller.show(document: document, page: 3)
        controller.view.layoutIfNeeded()
        XCTAssertTrue(controller.pdfView.currentPage === document.page(at: 2))

        controller.pdfView.go(to: try XCTUnwrap(document.page(at: 4)))
        controller.show(document: document, page: 3)
        controller.viewDidAppear(false)
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        XCTAssertTrue(controller.pdfView.currentPage === document.page(at: 4), "A SwiftUI refresh must preserve manual reading position")

        let replacement = try self.document()
        controller.show(document: replacement, page: 3)
        controller.view.layoutIfNeeded()
        XCTAssertTrue(controller.pdfView.currentPage === replacement.page(at: 2))
    }
}
