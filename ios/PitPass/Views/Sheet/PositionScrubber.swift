import SwiftUI
import UIKit

/// One continuous touch owns the gesture, even inside the table's scroll view.
/// Preview stays local until release so sorting cannot move the touched row.
struct PositionScrubber: UIViewRepresentable {
    let position: Int
    let maximum: Int
    let label: String
    let onCommit: (Int) -> Void

    func makeUIView(context: Context) -> PositionScrubberControl { PositionScrubberControl() }
    func updateUIView(_ view: PositionScrubberControl, context: Context) {
        view.position = position
        view.maximum = maximum
        view.accessibilityLabel = label
        view.onCommit = onCommit
        view.refreshLabel()
    }
    static func dismantleUIView(_ view: PositionScrubberControl, coordinator: ()) {
        view.finish(commit: false)
    }
}

final class PositionScrubberControl: UIView {
    var position = 0
    var maximum = 40
    var onCommit: (Int) -> Void = { _ in }
    private(set) var preview: Int?
    private var previousX: CGFloat = 0
    private var remainder: CGFloat = 0
    private var edgeDirection = 0
    private var edgeTimer: Timer?
    private let valueLabel = UILabel()
    private let bubble = UIView()
    private let bubbleTitle = UILabel()
    private let track = UISlider()
    private let help = UILabel()
    private var anchor: CGPoint?
    private let feedback = UISelectionFeedbackGenerator()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = .adjustable
        accessibilityHint = "Slide left or right to change position. Zero clears the position."
        accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Clear position", target: self, selector: #selector(clearPosition))]
        valueLabel.adjustsFontForContentSizeCategory = true
        valueLabel.textAlignment = .center
        valueLabel.font = .preferredFont(forTextStyle: .subheadline)
        valueLabel.textColor = UIColor(PP.accentInk)
        valueLabel.translatesAutoresizingMaskIntoConstraints = false
        let icon = UIImageView(image: UIImage(systemName: "arrow.left.and.right"))
        icon.tintColor = UIColor(PP.accentInk)
        icon.preferredSymbolConfiguration = .init(pointSize: 11)
        let stack = UIStackView(arrangedSubviews: [valueLabel, icon])
        stack.spacing = 5
        stack.alignment = .center
        stack.isUserInteractionEnabled = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor), stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 4),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -4),
        ])
        // Unlike a SwiftUI zero-distance drag, this recognizer claims the touch
        // before the enclosing UIScrollView's pan can cancel it.
        let press = UILongPressGestureRecognizer(target: self, action: #selector(handlePress))
        press.minimumPressDuration = 0
        press.allowableMovement = .greatestFiniteMagnitude
        addGestureRecognizer(press)
        configureBubble()
        registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (view: PositionScrubberControl, _: UITraitCollection) in
            if let anchor = view.anchor { view.placeBubble(at: anchor) }
        }
        refreshLabel()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func refreshLabel() {
        let value = preview ?? position
        valueLabel.text = value == 0 ? "—" : "P\(value)"
        accessibilityValue = value == 0 ? "No position" : "Position \(value)"
        bubbleTitle.text = value == 0 ? "No position" : "Position \(value)"
        track.maximumValue = Float(maximum)
        track.value = Float(value)
    }
    private func configureBubble() {
        bubble.isUserInteractionEnabled = false
        bubble.backgroundColor = UIColor(PP.surface)
        bubble.layer.cornerRadius = 12
        bubble.layer.shadowColor = UIColor.black.cgColor
        bubble.layer.shadowOpacity = 0.2
        bubble.layer.shadowOffset = CGSize(width: 0, height: 4)
        bubble.layer.shadowRadius = 12
        bubbleTitle.font = .preferredFont(forTextStyle: .headline)
        bubbleTitle.textColor = UIColor(PP.ink)
        bubbleTitle.textAlignment = .center
        bubbleTitle.adjustsFontForContentSizeCategory = true
        bubbleTitle.numberOfLines = 0
        track.minimumValue = 0
        track.tintColor = UIColor(PP.accent)
        help.text = "Slide to adjust · release to set"
        help.font = .preferredFont(forTextStyle: .caption1)
        help.textColor = UIColor(PP.textMuted)
        help.textAlignment = .center
        help.adjustsFontForContentSizeCategory = true
        help.numberOfLines = 0
        let content = UIStackView(arrangedSubviews: [bubbleTitle, track, help])
        content.axis = .vertical
        content.spacing = 6
        content.translatesAutoresizingMaskIntoConstraints = false
        bubble.addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -16),
            content.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 12),
            content.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -12),
        ])
    }
    @objc private func handlePress(_ recognizer: UILongPressGestureRecognizer) {
        guard let window else { finish(commit: false); return }
        let point = recognizer.location(in: window)
        switch recognizer.state {
        case .began: begin(at: point)
        case .changed: move(to: point)
        case .ended: move(to: point); finish(commit: true)
        case .cancelled, .failed: finish(commit: false)
        default: break
        }
    }
    func begin(at point: CGPoint) {
        guard let window, preview == nil else { return }
        preview = position
        previousX = point.x
        remainder = 0
        edgeDirection = 0
        anchor = point
        refreshLabel()
        placeBubble(at: point)
        window.addSubview(bubble)
        feedback.prepare()
        refreshLabel()
        let timer = Timer(timeInterval: 0.16, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.advanceAtEdge() }
        }
        edgeTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }
    private func placeBubble(at point: CGPoint) {
        guard let window else { return }
        bubble.traitOverrides.preferredContentSizeCategory = traitCollection.preferredContentSizeCategory
        bubble.overrideUserInterfaceStyle = traitCollection.userInterfaceStyle
        bubbleTitle.font = .preferredFont(forTextStyle: .headline, compatibleWith: traitCollection)
        help.font = .preferredFont(forTextStyle: .caption1, compatibleWith: traitCollection)
        bubble.layoutIfNeeded()
        let safe = window.bounds.inset(by: window.safeAreaInsets).insetBy(dx: 12, dy: 12)
        let width = min(340, safe.width)
        let labelSize = CGSize(width: width - 32, height: .greatestFiniteMagnitude)
        let height = bubbleTitle.sizeThatFits(labelSize).height + help.sizeThatFits(labelSize).height
            + track.intrinsicContentSize.height + 36
        let x = min(max(point.x - width / 2, safe.minX), safe.maxX - width)
        // Keep the preview above the finger, or below it if near the top edge.
        let y = point.y - height - 36 >= safe.minY ? point.y - height - 36 : min(point.y + 44, safe.maxY - height)
        bubble.frame = CGRect(x: x, y: y, width: width, height: height)
    }
    func move(to point: CGPoint) {
        guard preview != nil, let window else { return }
        remainder += (point.x - previousX) / 12
        previousX = point.x
        let steps = Int(remainder)
        if steps != 0 {
            remainder -= CGFloat(steps)
            setPreview((preview ?? position) + steps)
        }
        let safe = window.bounds.inset(by: window.safeAreaInsets)
        // At an edge, keep advancing without requiring a lift and a second drag.
        edgeDirection = point.x <= safe.minX + 20 ? -1 : point.x >= safe.maxX - 20 ? 1 : 0
    }
    private func advanceAtEdge() {
        guard let preview, edgeDirection != 0 else { return }
        setPreview(preview + edgeDirection)
    }
    private func setPreview(_ value: Int) {
        let next = min(maximum, max(0, value))
        guard next != preview else { return }
        preview = next
        feedback.selectionChanged()
        refreshLabel()
        if let anchor { placeBubble(at: anchor) }
    }
    func finish(commit: Bool) {
        let chosen = preview
        preview = nil
        anchor = nil
        edgeTimer?.invalidate()
        edgeTimer = nil
        edgeDirection = 0
        bubble.removeFromSuperview()
        if commit, let chosen, chosen != position {
            position = chosen
            onCommit(chosen)
        }
        refreshLabel()
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { finish(commit: false) }
    }
    override func accessibilityIncrement() { commitAccessible(position + 1) }
    override func accessibilityDecrement() { commitAccessible(position - 1) }
    @objc private func clearPosition() -> Bool { commitAccessible(0); return true }
    private func commitAccessible(_ value: Int) {
        finish(commit: false)
        position = min(maximum, max(0, value))
        refreshLabel()
        onCommit(position)
    }
}
