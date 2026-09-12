import SwiftUI

/// The Pit Pass "access lane" mark, ported from frontend/public/
/// pit-pass-access-lane.svg (328×328 space): two offset P forms, the lane
/// rail, and the gold access bridge. The mark is always light ink, so on a
/// light surface it sits on its dark plate and on a dark surface it goes
/// plate-less — the website's `--brand-mark` rule.
struct BrandMark: View {
    @Environment(\.colorScheme) private var colorScheme
    var size: CGFloat = 28

    var body: some View {
        ZStack {
            if colorScheme == .light {
                RoundedRectangle(cornerRadius: size * 0.12, style: .continuous)
                    .fill(LinearGradient(colors: [PP.brandPlateTop, PP.brandPlateBottom],
                                         startPoint: .top, endPoint: .bottom))
            }
            MarkShape().fill(.white)
            BridgeShape().fill(PP.brandBridge)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Pit Pass")
    }

    private struct MarkShape: Shape {
        func path(in rect: CGRect) -> Path {
            let k = rect.width / 328
            var p = Path()
            // Left P.
            p.move(to: CGPoint(x: 49, y: 85))
            p.addLine(to: CGPoint(x: 112, y: 85))
            p.addCurve(to: CGPoint(x: 148, y: 121), control1: CGPoint(x: 133, y: 85), control2: CGPoint(x: 148, y: 100))
            p.addCurve(to: CGPoint(x: 112, y: 157), control1: CGPoint(x: 148, y: 142), control2: CGPoint(x: 133, y: 157))
            p.addLine(to: CGPoint(x: 72, y: 157))
            p.addLine(to: CGPoint(x: 72, y: 199))
            p.addLine(to: CGPoint(x: 49, y: 199))
            p.addLine(to: CGPoint(x: 49, y: 134))
            p.addLine(to: CGPoint(x: 112, y: 134))
            p.addCurve(to: CGPoint(x: 126, y: 121), control1: CGPoint(x: 120, y: 134), control2: CGPoint(x: 126, y: 129))
            p.addCurve(to: CGPoint(x: 112, y: 108), control1: CGPoint(x: 126, y: 113), control2: CGPoint(x: 120, y: 108))
            p.addLine(to: CGPoint(x: 49, y: 108))
            p.closeSubpath()
            // Right P — deliberately lower and slightly narrower.
            p.move(to: CGPoint(x: 202, y: 110))
            p.addLine(to: CGPoint(x: 262, y: 110))
            p.addCurve(to: CGPoint(x: 297, y: 147), control1: CGPoint(x: 283, y: 110), control2: CGPoint(x: 297, y: 125))
            p.addCurve(to: CGPoint(x: 261, y: 184), control1: CGPoint(x: 297, y: 168), control2: CGPoint(x: 282, y: 184))
            p.addLine(to: CGPoint(x: 224, y: 184))
            p.addLine(to: CGPoint(x: 224, y: 228))
            p.addLine(to: CGPoint(x: 202, y: 228))
            p.addLine(to: CGPoint(x: 202, y: 161))
            p.addLine(to: CGPoint(x: 261, y: 161))
            p.addCurve(to: CGPoint(x: 275, y: 147), control1: CGPoint(x: 269, y: 161), control2: CGPoint(x: 275, y: 155))
            p.addCurve(to: CGPoint(x: 261, y: 133), control1: CGPoint(x: 275, y: 139), control2: CGPoint(x: 269, y: 133))
            p.addLine(to: CGPoint(x: 202, y: 133))
            p.closeSubpath()
            // The lane rail.
            p.addRect(CGRect(x: 161, y: 51, width: 24, height: 210))
            return p.applying(CGAffineTransform(scaleX: k, y: k).translatedBy(x: rect.minX / k, y: rect.minY / k))
        }
    }

    private struct BridgeShape: Shape {
        func path(in rect: CGRect) -> Path {
            let k = rect.width / 328
            return Path(roundedRect: CGRect(x: 145 * k, y: 161 * k, width: 46 * k, height: 22 * k),
                        cornerRadius: 1.5 * k)
        }
    }
}

/// "Pit **Pass**" — the topbar wordmark: mark + name, "Pass" in amber ink.
struct Wordmark: View {
    var markSize: CGFloat = 28
    var textSize: CGFloat = PP.TextSize.base

    var body: some View {
        HStack(spacing: PP.Space.s2) {
            BrandMark(size: markSize)
            (Text("Pit ").foregroundColor(PP.ink) + Text("Pass").foregroundColor(PP.accentInk))
                .font(PP.sans(textSize, weight: 650))
                .tracking(textSize * -0.01)
        }
    }
}
