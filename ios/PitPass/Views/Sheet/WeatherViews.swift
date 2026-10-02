import Charts
import SwiftUI

// The track's weather station from the Al Kamel feed (web: Weather.tsx): a
// strip above the tower with the latest reading, and the session's readings
// (one a minute) as their own view — track and air temperature over the
// session, how far each has moved since the first reading, and every reading
// in a list. Units follow the feed's: °F, mph and inHg when it times in mph.

private struct Part: Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

/// Track, air, humidity, wind, pressure — whichever the station sent.
private func parts(_ r: WeatherReading, us: Bool) -> [Part] {
    [("Track", TimingFormat.temperature(c: r.trackC, f: r.trackF, us: us)),
     ("Air", TimingFormat.temperature(c: r.airC, f: r.airF, us: us)),
     ("Humidity", r.humidityPct.map { "\(Int($0.rounded()))%" }),
     ("Wind", TimingFormat.wind(r, us: us)),
     ("Pressure", TimingFormat.pressure(r, us: us))]
        .compactMap { label, value in value.map { Part(label: label, value: $0) } }
}

struct WeatherStrip: View {
    let now: WeatherReading?
    let us: Bool
    let showAll: () -> Void

    var body: some View {
        if let now, case let shown = parts(now, us: us), !shown.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
                Text("WEATHER")
                    .font(.caption2.weight(.semibold)).tracking(0.5)
                    .foregroundStyle(PP.textMuted)
                HStack(alignment: .firstTextBaseline, spacing: PP.Space.s4) {
                    ForEach(shown) { p in
                        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s1) {
                            Text(p.label).font(.caption).foregroundStyle(PP.textMuted)
                            Text(p.value).font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(PP.ink)
                        }
                        .fixedSize()
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button("Over the session", action: showAll).font(.caption)
            }
            .padding(.vertical, PP.Space.s2).padding(.horizontal, PP.Space.s3)
            .background(PP.surface, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(PP.border))
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Weather")
        }
    }
}

/// The session's readings. Times are at the track when its offset is known (the live session).
struct WeatherSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let us: Bool
    let utcOffsetHours: Double?
    @State private var feed = LiveFeed<WeatherLog>()

    private struct Series: Identifiable {
        let label: String
        let color: Color
        let value: (WeatherReading) -> Double?
        var id: String { label }
    }

    private var series: [Series] {
        [Series(label: "Track", color: GapSeries.colors[1], value: { us ? $0.trackF : $0.trackC }),
         Series(label: "Air", color: GapSeries.colors[0], value: { us ? $0.airF : $0.airC })]
    }

    var body: some View {
        Group {
            if let value = feed.value {
                let readings = value.readings.filter { $0.dayTimeMs != nil }
                if readings.isEmpty {
                    EmptyState(message: "No weather recorded for this session.")
                } else {
                    VStack(alignment: .leading, spacing: PP.Space.s4) {
                        if let error = feed.error {
                            Text("Not updating: \(error)").font(.caption).foregroundStyle(PP.error).lineLimit(1)
                        }
                        summary(readings)
                        if readings.count > 1 { chart(readings) }
                        list(readings)
                        Text("Degrees \(us ? "Fahrenheit" : "Celsius"), as the feed times. Wind is the direction it blows from."
                             + (utcOffsetHours == nil ? " Times are this iPad's clock: the track's offset is known only while the session is live." : ""))
                            .font(.caption).foregroundStyle(PP.textMuted)
                    }
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load the weather: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await feed.run(session.client, path: "/api/live/weather?session=\(chosen.sessionDbId)",
                           every: chosen.current ? .seconds(30) : .seconds(120))
        }
    }

    private static func signed(_ v: Double) -> String {
        (v > 0 ? "+" : v < 0 ? "−" : "±") + String(format: "%.1f°", abs(v))
    }

    /// Where track and air stand now, and how far each has moved since the first reading.
    private func summary(_ readings: [WeatherReading]) -> some View {
        HStack(alignment: .top, spacing: PP.Space.s6) {
            ForEach(series) { s in
                let vals = readings.compactMap(s.value)
                if let first = vals.first, let last = vals.last, let lo = vals.min(), let hi = vals.max() {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 1).fill(s.color).frame(width: 10, height: 3)
                            Text(s.label.uppercased()).font(.caption2.weight(.semibold)).tracking(0.5).foregroundStyle(PP.textMuted)
                        }
                        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s1) {
                            Text(String(format: "%.1f°", last)).font(PP.sans(PP.TextSize.xl, weight: 600).monospacedDigit())
                                .foregroundStyle(PP.ink)
                            Text("\(Self.signed(last - first)) since \(String(format: "%.1f°", first))")
                                .font(.subheadline.monospacedDigit()).foregroundStyle(PP.text)
                        }
                        Text(String(format: "Low %.1f° · high %.1f°", lo, hi)).font(.caption).foregroundStyle(PP.textMuted)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private struct Point: Identifiable {
        let series: String
        let time: Date
        let value: Double
        let run: Int
        var id: String { "\(series)#\(time.timeIntervalSince1970)" }
    }

    /// One line each, broken where a reading lacks the value; whole-degree axis at least 4° tall.
    private func chart(_ readings: [WeatherReading]) -> some View {
        let points: [Point] = series.flatMap { s in
            var run = 0
            var pen = false
            return readings.compactMap { r -> Point? in
                guard let v = s.value(r) else { pen = false; return nil }
                if !pen { run += 1; pen = true }
                return Point(series: s.label, time: Date(timeIntervalSince1970: Double(r.dayTimeMs!) / 1000), value: v, run: run)
            }
        }
        var lo = (points.map(\.value).min() ?? 0).rounded(.down)
        var hi = (points.map(\.value).max() ?? 4).rounded(.up)
        if hi - lo < 4 { let mid = (hi + lo) / 2; lo = (mid - 2).rounded(.down); hi = lo + 4 }
        let colors = Dictionary(uniqueKeysWithValues: series.map { ($0.label, $0.color) })
        let lastPoints = series.compactMap { s in points.last { $0.series == s.label } }
        return Chart {
            ForEach(points) { p in
                LineMark(x: .value("Time", p.time), y: .value("Temperature", p.value), series: .value("Line", "\(p.series)#\(p.run)"))
                    .foregroundStyle(colors[p.series] ?? PP.ink)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            ForEach(lastPoints) { p in
                PointMark(x: .value("Time", p.time), y: .value("Temperature", p.value))
                    .symbolSize(30)
                    .foregroundStyle(colors[p.series] ?? PP.ink)
                    .annotation(position: .trailing, spacing: 4) {
                        Text(p.series).font(PP.mono(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.ink)
                    }
            }
        }
        .chartYScale(domain: lo...hi)
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 5)) { v in
                AxisGridLine().foregroundStyle(PP.border)
                AxisValueLabel {
                    if let d = v.as(Double.self) { Text("\(Int(d))°").font(PP.mono(11)).foregroundStyle(PP.textMuted) }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 6)) { v in
                AxisValueLabel {
                    if let d = v.as(Date.self),
                       let t = TimingFormat.messageTime(dayTimeMs: Int(d.timeIntervalSince1970 * 1000), utcOffsetHours: utcOffsetHours) {
                        Text(String(t.dropLast(3))).font(PP.mono(11)).foregroundStyle(PP.textMuted)
                    }
                }
            }
        }
        .padding(.trailing, 48) // room for the line labels
        .frame(height: 240)
        .accessibilityLabel("Track and air temperature over the session. Every reading is listed below.")
    }

    /// Every reading, newest first.
    private func list(_ readings: [WeatherReading]) -> some View {
        DisclosureGroup("Every reading (\(readings.count))") {
            VStack(alignment: .leading, spacing: 0) {
                row(["Time", "Track", "Air", "Humidity", "Wind", "Pressure"], header: true)
                ForEach(readings.reversed(), id: \.dayTimeMs) { r in
                    Divider().overlay(PP.border)
                    row([TimingFormat.messageTime(dayTimeMs: r.dayTimeMs, utcOffsetHours: utcOffsetHours) ?? "—",
                         TimingFormat.temperature(c: r.trackC, f: r.trackF, us: us) ?? "—",
                         TimingFormat.temperature(c: r.airC, f: r.airF, us: us) ?? "—",
                         r.humidityPct.map { "\(Int($0.rounded()))%" } ?? "—",
                         TimingFormat.wind(r, us: us) ?? "—",
                         TimingFormat.pressure(r, us: us) ?? "—"], header: false)
                }
            }
            .background(PP.bg)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(PP.border))
            .padding(.top, PP.Space.s2)
        }
        .font(.subheadline)
        .foregroundStyle(PP.textMuted)
    }

    private func row(_ cells: [String], header: Bool) -> some View {
        HStack(spacing: PP.Space.s3) {
            ForEach(Array(cells.enumerated()), id: \.offset) { i, c in
                Text(c)
                    .font(header ? .caption.weight(.semibold) : .subheadline.monospacedDigit())
                    .foregroundStyle(header ? PP.textMuted : PP.ink)
                    .frame(width: i == 4 ? 120 : i == 5 ? 110 : 80, alignment: .leading)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, PP.Space.s2).padding(.horizontal, PP.Space.s3)
        .accessibilityElement(children: .combine)
    }
}
