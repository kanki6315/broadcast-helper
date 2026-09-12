import SwiftUI
import UIKit

/// The website's design tokens ("The Timing Tower", DESIGN.md /
/// frontend/src/index.css) as Swift. Values are the sRGB approximations
/// DESIGN.md publishes for tooling; every pair keeps the site's AA contrast.
/// Light and dark are both first-class — the dark values are the booth.
enum PP {
    // MARK: Colors (light, dark)

    static let bg = dynamic(0xFFFFFF, 0x16171D)
    static let surface = dynamic(0xF3F4F7, 0x1E2027)
    static let surface2 = dynamic(0xE9EBEF, 0x262933)
    static let border = dynamic(0xDFE0E5, 0x2E303A)
    static let borderStrong = dynamic(0xCCCFD6, 0x3B3E4A)

    static let ink = dynamic(0x0B0812, 0xF3F4F6)
    static let text = dynamic(0x3D3A45, 0xB6BCC7)
    static let textMuted = dynamic(0x5E626E, 0x878E9E)

    /// Broadcast amber: the one instrument light. Fills.
    static let accent = dynamic(0xB8791F, 0xF0B84A)
    /// Amber legible AS TEXT (darkened on light; bright enough on dark).
    static let accentInk = dynamic(0x8F5E12, 0xF0B84A)
    /// Selection wash.
    static let accentTint = dynamic(0xB8791F, 0xF0B84A, lightAlpha: 0.10, darkAlpha: 0.12)
    static let onAccent = dynamic(0x221806, 0x2A2008)

    static let error = dynamic(0xB32318, 0xF5776B)
    static let errorTint = dynamic(0xB32318, 0xF5776B, lightAlpha: 0.09, darkAlpha: 0.13)
    static let success = dynamic(0x067647, 0x47CD89)
    static let info = dynamic(0x1758D3, 0x7FA8F2)

    /// The brand mark's dark plate (light theme only; on dark it goes plate-less).
    static let brandPlateTop = Color(hex: 0x1C1F28)
    static let brandPlateBottom = Color(hex: 0x15171E)
    static let brandBridge = Color(hex: 0xF3AF38)

    // MARK: Type scale (fixed, ~1.2 ratio — product UI, never fluid)

    enum TextSize {
        static let xs: CGFloat = 12
        static let sm: CGFloat = 14
        static let base: CGFloat = 16
        static let lg: CGFloat = 20
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 30
    }

    // MARK: Spacing (4pt scale)

    enum Space {
        static let s1: CGFloat = 4
        static let s2: CGFloat = 8
        static let s3: CGFloat = 12
        static let s4: CGFloat = 16
        static let s5: CGFloat = 24
        static let s6: CGFloat = 32
        static let s7: CGFloat = 48
        static let s8: CGFloat = 64
    }

    enum Radius {
        static let xs: CGFloat = 3
        static let sm: CGFloat = 4
        static let md: CGFloat = 6
        static let lg: CGFloat = 10
    }

    /// Booth-fast motion: 70ms state feedback, 140ms transitions, ease-out-quart.
    enum Motion {
        static let fast: Animation = .timingCurve(0.25, 1, 0.5, 1, duration: 0.07)
        static let medium: Animation = .timingCurve(0.25, 1, 0.5, 1, duration: 0.14)
    }

    // MARK: Fonts

    /// Inter (UI). `weight` is the variable axis value, e.g. 400 / 500 / 600 / 650.
    static func sans(_ size: CGFloat, weight: CGFloat = 400) -> Font {
        Font(variable("Inter-Regular", size: size, weight: weight))
    }

    /// JetBrains Mono (data): every number that lives in a column.
    static func mono(_ size: CGFloat, weight: CGFloat = 400) -> Font {
        Font(variable("JetBrainsMono-Regular", size: size, weight: weight))
    }

    private static func variable(_ postScriptName: String, size: CGFloat, weight: CGFloat) -> UIFont {
        // 'wght' as a four-char code — the variation axis both bundled faces expose.
        let wght = 0x7767_6874
        let descriptor = UIFontDescriptor(fontAttributes: [
            .name: postScriptName,
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [wght: weight],
        ])
        return UIFont(descriptor: descriptor, size: size)
    }

    // MARK: plumbing

    private static func dynamic(_ light: UInt32, _ dark: UInt32,
                                lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(hex: dark, alpha: darkAlpha)
                : UIColor(hex: light, alpha: lightAlpha)
        })
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: alpha)
    }
}

extension Color {
    init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(uiColor: UIColor(hex: hex, alpha: alpha))
    }

    /// A user-configured class colour from the API ("#e30d0d"). Hue is data
    /// here, never decoration — always shown next to its code.
    init?(cssHex: String) {
        var text = cssHex.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("#") { text.removeFirst() }
        if text.count == 3 { text = text.map { "\($0)\($0)" }.joined() }
        guard text.count == 6, let value = UInt32(text, radix: 16) else { return nil }
        self.init(hex: value)
    }
}

// MARK: - Text styles (the DESIGN.md hierarchy)

extension View {
    /// Headline — the page title. 600, 24pt, -0.02em.
    func ppHeadline() -> some View {
        font(PP.sans(PP.TextSize.xl, weight: 600)).tracking(PP.TextSize.xl * -0.02).foregroundStyle(PP.ink)
    }

    /// Title — section headings. 600, 20pt.
    func ppTitle() -> some View {
        font(PP.sans(PP.TextSize.lg, weight: 600)).tracking(PP.TextSize.lg * -0.02).foregroundStyle(PP.ink)
    }

    /// Body — prose and labels. 400, 16pt.
    func ppBody() -> some View {
        font(PP.sans(PP.TextSize.base)).foregroundStyle(PP.text)
    }

    /// Label — buttons, chips, controls. 500, 14pt.
    func ppLabel() -> some View {
        font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.text)
    }

    /// Caption — table headers, legends. 600, 12pt, muted. Sentence case, never tracked caps.
    func ppCaption() -> some View {
        font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
    }
}

/// Theme preference, mirroring the website's Auto / Light / Dark toggle.
enum ThemePreference: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Auto"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    private static let key = "pitpass.theme"

    static var stored: ThemePreference {
        get { ThemePreference(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .system }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }
}
