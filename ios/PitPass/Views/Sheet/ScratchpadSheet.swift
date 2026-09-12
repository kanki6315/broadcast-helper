import PencilKit
import SwiftUI

/// The Pencil scratchpad over the event sheet (ScratchpadModal.tsx): the
/// tool row (Pen/Eraser, the six literal ink colours, S/M/L, Undo/Redo), the
/// conflict banner, and white paper 800 logical px wide scaled to the
/// window. Ink colours are persisted literals, so the paper stays white in
/// both themes; the chrome follows the tokens.
struct ScratchpadSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let eventId: Int
    @State private var model: PadModel?
    @State private var tool: Tool? = .pen
    @State private var color = ScratchpadSheet.colors[0].value
    @State private var size: Double? = ScratchpadSheet.sizes[1].value

    enum Tool: String, CaseIterable, Identifiable {
        case pen, eraser
        var id: String { rawValue }
    }

    /// Literal ink colours, the web's list.
    static let colors: [(value: String, name: String)] = [
        ("#111827", "Black"), ("#dc2626", "Red"), ("#2563eb", "Blue"),
        ("#16a34a", "Green"), ("#ea580c", "Orange"), ("#9333ea", "Purple"),
    ]
    static let sizes: [(label: String, value: Double)] = [("S", 2), ("M", 4), ("L", 8)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let model {
                    toolbar(model)
                    if model.conflict { conflictBanner(model) }
                    switch model.phase {
                    case .ready:
                        PadCanvas(model: model, tool: tool ?? .pen, color: color, size: size ?? 4)
                            .ignoresSafeArea(edges: .bottom)
                    case .loading:
                        Text("Loading scratchpad…").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    case let .error(message):
                        VStack(spacing: PP.Space.s3) {
                            ErrorPanel(message: "Failed to load the scratchpad. \(message)")
                            RetryButton { Task { await model.load() } }
                        }
                        .padding(PP.Space.s5)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                } else {
                    Color.clear
                }
            }
            .background(PP.surface.ignoresSafeArea())
            .navigationTitle("Scratchpad")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(PP.surface, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { close() }.font(PP.sans(PP.TextSize.sm, weight: 600)).tint(PP.accentInk)
                }
            }
        }
        .tint(PP.accentInk)
        .task {
            let m = PadModel(eventId: eventId, session: session)
            model = m
            await m.load()
        }
        .onDisappear { model?.close() }
    }

    private func close() {
        model?.close()
        dismiss()
    }

    // MARK: chrome

    private func toolbar(_ model: PadModel) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: PP.Space.s3) { tools(model) }
            VStack(alignment: .leading, spacing: PP.Space.s2) {
                HStack(spacing: PP.Space.s3) { toolPick; swatches }
                HStack(spacing: PP.Space.s3) { sizePick; statusText(model.status); Spacer(); undoRedo(model) }
            }
        }
        .padding(.vertical, PP.Space.s2)
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
    }

    @ViewBuilder private func tools(_ model: PadModel) -> some View {
        toolPick
        swatches
        sizePick
        statusText(model.status)
        Spacer(minLength: PP.Space.s3)
        undoRedo(model)
    }

    private var toolPick: some View {
        Segmented(options: [.init(id: Tool.pen, label: "Pen"), .init(id: Tool.eraser, label: "Eraser")], selection: $tool)
            .accessibilityLabel("Tool")
    }

    /// `.sp-swatch`: 22pt discs with a hairline; the active one wears the
    /// amber selection ring — amber names the choice, never the ink.
    private var swatches: some View {
        HStack(spacing: 6) {
            ForEach(ScratchpadSheet.colors, id: \.value) { c in
                let active = c.value == color && tool == .pen
                Button {
                    withAnimation(PP.Motion.fast) {
                        color = c.value
                        tool = .pen
                    }
                } label: {
                    Circle()
                        .fill(Color(UIColor(hex: c.value) ?? .black))
                        .overlay(Circle().strokeBorder(PP.borderStrong))
                        .frame(width: 22, height: 22)
                        .padding(3)
                        .overlay {
                            if active { Circle().strokeBorder(PP.accent, lineWidth: 2) }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(c.name)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Pen colour")
    }

    private var sizePick: some View {
        Segmented(options: ScratchpadSheet.sizes.map { .init(id: $0.value, label: $0.label) }, selection: $size)
            .accessibilityLabel("Pen size")
    }

    private func undoRedo(_ model: PadModel) -> some View {
        HStack(spacing: PP.Space.s2) {
            Button("Undo") { model.undoHandle?() }
                .buttonStyle(PPSecondaryButtonStyle())
                .disabled(!model.canUndo)
                .opacity(model.canUndo ? 1 : 0.5)
            Button("Redo") { model.redoHandle?() }
                .buttonStyle(PPSecondaryButtonStyle())
                .disabled(!model.canRedo)
                .opacity(model.canRedo ? 1 : 0.5)
            Button("Extend page") { model.extendPage() }
                .buttonStyle(PPSecondaryButtonStyle())
                .disabled(!model.canExtend)
        }
    }

    /// `.sp-conflict`: the pad is read-only-in-effect until a side is picked.
    private func conflictBanner(_ model: PadModel) -> some View {
        HStack(spacing: PP.Space.s3) {
            Text("This pad changed in another tab or on another device. Keep one — the other is kept as a local backup.")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
            Spacer(minLength: PP.Space.s3)
            Button("Keep this device’s ink") { Task { await model.resolveConflict(keepMine: true) } }
                .buttonStyle(PPSecondaryButtonStyle())
            Button("Use other version") { Task { await model.resolveConflict(keepMine: false) } }
                .buttonStyle(PPSecondaryButtonStyle())
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 14)
        .background(PP.accentTint)
        .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
    }

    /// `.sp-save-status`: quiet, only visible while there's something to say.
    @ViewBuilder private func statusText(_ status: PadModel.SaveStatus) -> some View {
        let text: String? = switch status {
        case .idle: nil
        case .saving: "Saving…"
        case .saved: "Saved"
        case .error: "Save failed — will retry"
        case .full: "Pad full — erase some strokes"
        case .conflict: "Changed elsewhere"
        case .offline: "Offline — saved on this iPad"
        }
        if let text {
            Text(text)
                .font(PP.sans(PP.TextSize.xs))
                .foregroundStyle(status == .error || status == .full ? PP.error : PP.textMuted)
                .lineLimit(1)
                .padding(.leading, PP.Space.s1)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }
}

// MARK: - the canvas

/// PencilKit as the paper: a `PKCanvasView` whose content is the 800-wide
/// logical column at a fixed zoom that fills the window, so PencilKit's
/// coordinates *are* the wire format's. Monoline ink matches the web's
/// uniform-width strokes; the vector eraser removes whole strokes like the
/// web's eraser. Fingers scroll or draw per the system Pencil setting.
private struct PadCanvas: UIViewRepresentable {
    let model: PadModel
    let tool: ScratchpadSheet.Tool
    let color: String
    let size: Double

    func makeUIView(context: Context) -> FittedCanvasView {
        let view = FittedCanvasView()
        view.delegate = context.coordinator
        #if targetEnvironment(simulator)
        // The simulator reports "Only Draw with Apple Pencil" on, which would
        // leave a mouse unable to draw at all.
        view.drawingPolicy = .anyInput
        #else
        view.drawingPolicy = .default
        #endif
        view.backgroundColor = .white
        view.overrideUserInterfaceStyle = .light
        view.alwaysBounceVertical = true
        view.isRulerActive = false
        model.undoHandle = { [weak view] in view?.undoManager?.undo(); view?.reportUndoState() }
        model.redoHandle = { [weak view] in view?.undoManager?.redo(); view?.reportUndoState() }
        view.onUndoState = { [weak model] canUndo, canRedo in model?.noteUndoState(canUndo: canUndo, canRedo: canRedo) }
        return view
    }

    func updateUIView(_ view: FittedCanvasView, context: Context) {
        view.pageHeight = CGFloat(model.pageHeight)
        if context.coordinator.appliedVersion != model.drawingVersion {
            context.coordinator.appliedVersion = model.drawingVersion
            // Before the first real layout the canvas sits at a placeholder
            // zoom; a drawing installed then is rasterised at that scale and
            // stays blurry, so it waits for the fit.
            view.install(model.drawing)
            view.reportUndoState()
        }
        switch tool {
        case .pen:
            view.tool = PKInkingTool(.monoline, color: UIColor(hex: color) ?? .black, width: size)
        case .eraser:
            view.tool = PKEraserTool(.vector, width: Pad.eraserRadius * 2)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        let model: PadModel
        var appliedVersion = -1
        /// True while the representable itself sets the drawing.
        var muted = false

        init(model: PadModel) { self.model = model }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            guard !muted else { return }
            let undo = canvasView.undoManager
            model.canvasChanged(canvasView.drawing, canUndo: undo?.canUndo ?? false, canRedo: undo?.canRedo ?? false)
        }
    }
}

/// Keeps the 800-logical-px column fitted to its width across layout and
/// page growth (Apple's PencilKit sample sizes content the same way).
final class FittedCanvasView: PKCanvasView {
    var pageHeight: CGFloat = CGFloat(Pad.defaultPageHeight) { didSet { if pageHeight != oldValue { setNeedsLayout() } } }
    var onUndoState: ((Bool, Bool) -> Void)?
    private var pending: PKDrawing?

    /// Replace the document, dropping PencilKit's undo history.
    func install(_ drawing: PKDrawing) {
        if bounds.width > 0 {
            self.drawing = drawing
            undoManager?.removeAllActions()
        } else {
            pending = drawing
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0 else { return }
        let scale = bounds.width / Pad.width
        let size = CGSize(width: Pad.width * scale, height: pageHeight * scale)
        if minimumZoomScale != scale || maximumZoomScale != scale {
            minimumZoomScale = scale
            maximumZoomScale = scale
            zoomScale = scale
            contentSize = size
            // Re-zooming shifts the offset to keep the old centre; the first
            // fit (from a zero-size layout) would otherwise land mid-page.
            contentOffset = CGPoint(x: 0, y: -adjustedContentInset.top)
        }
        if contentSize != size { contentSize = size }
        if let pending {
            self.pending = nil
            drawing = pending
            undoManager?.removeAllActions()
        }
    }

    func reportUndoState() {
        onUndoState?(undoManager?.canUndo ?? false, undoManager?.canRedo ?? false)
    }
}
