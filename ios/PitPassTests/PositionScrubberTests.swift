import SwiftUI
import XCTest
@testable import PitPass

@MainActor
final class PositionScrubberTests: XCTestCase {
    private func host(width: CGFloat = 834) throws -> (UIWindow, PositionScrubberControl) {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: width, height: 700)
        let controller = UIViewController()
        controller.view.backgroundColor = UIColor(PP.bg)
        window.rootViewController = controller
        window.isHidden = false
        let control = PositionScrubberControl(frame: CGRect(x: width / 2 - 40, y: 340, width: 80, height: 44))
        controller.view.addSubview(control)
        return (window, control)
    }
    func testContinuousDragPreviewsThenCommitsOnce() throws {
        let (window, control) = try host()
        defer { window.isHidden = true }
        control.position = 5
        var commits: [Int] = []
        control.onCommit = { commits.append($0) }
        control.begin(at: CGPoint(x: 400, y: 360))
        control.move(to: CGPoint(x: 436, y: 360))
        XCTAssertEqual(control.preview, 8)
        XCTAssertEqual(control.position, 5)
        XCTAssertTrue(commits.isEmpty, "The table must not re-sort under a held finger")
        control.move(to: CGPoint(x: 424, y: 360))
        XCTAssertEqual(control.preview, 7)
        control.finish(commit: true)
        XCTAssertEqual(commits, [7])
        XCTAssertNil(control.preview)
        control.finish(commit: true)
        XCTAssertEqual(commits, [7])
    }
    func testClearBoundsCancellationAndAccessibility() throws {
        let (window, control) = try host()
        defer { window.isHidden = true }
        control.position = 2
        var commits: [Int] = []
        control.onCommit = { commits.append($0) }
        control.begin(at: CGPoint(x: 400, y: 360))
        control.move(to: CGPoint(x: 100, y: 360))
        XCTAssertEqual(control.preview, 0)
        control.finish(commit: true)
        XCTAssertEqual(commits, [0])
        control.begin(at: CGPoint(x: 100, y: 360))
        control.move(to: CGPoint(x: 800, y: 360))
        XCTAssertEqual(control.preview, 40)
        control.finish(commit: false)
        XCTAssertEqual(control.position, 0)
        XCTAssertEqual(commits, [0])
        control.accessibilityIncrement()
        XCTAssertEqual(commits, [0, 1])
        control.accessibilityDecrement()
        XCTAssertEqual(commits, [0, 1, 0])
        // A press and release without horizontal movement makes no assignment.
        control.begin(at: CGPoint(x: 400, y: 360))
        control.move(to: CGPoint(x: 400, y: 500))
        control.finish(commit: true)
        XCTAssertEqual(commits, [0, 1, 0])
    }
    func testActiveSliderLayouts() throws {
        for width in [CGFloat(1194), 507] {
            let (window, control) = try host(width: width)
            window.overrideUserInterfaceStyle = width == 507 ? .light : .dark
            if width == 507 {
                window.rootViewController?.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge
            }
            window.layoutIfNeeded()
            control.position = 5
            control.accessibilityLabel = "Race position for selected team"
            control.begin(at: CGPoint(x: width / 2, y: 360))
            control.move(to: CGPoint(x: width / 2 + 36, y: 360))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = "Active position slider \(width)"
            attachment.lifetime = .keepAlways
            add(attachment)
            try image.pngData()?.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("position-slider-\(Int(width)).png"))
            control.finish(commit: false)
            window.isHidden = true
        }
    }
}
