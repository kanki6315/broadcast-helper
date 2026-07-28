import type { CareerTotals, NamedFormatLine, QualiLine, SeasonStatLine, SeriesStatLine } from '../lib/api'

/** The career grains the driver and team stats endpoints share. */
export interface CareerStatsData {
  career: CareerTotals
  bySeries: SeriesStatLine[]
  seasons: SeasonStatLine[]
}

/** "Sprint 3W 4P3 · Main 2W 2P3 · 2 poles" — a format's line only names the
 * numbers it has; a season of zeros still shows its starts so the line reads
 * as participation, not absence. */
function formatSplit(byFormat: NamedFormatLine[], quali: QualiLine): string {
  const parts = byFormat
    .filter((l) => l.starts > 0)
    .map((l) => {
      const bits = [`${l.formatName}`]
      bits.push(`${l.wins}W`)
      if (l.podiums > 0) bits.push(`${l.podiums}P3`)
      return bits.join(' ')
    })
  if (quali.poles > 0) parts.push(`${quali.poles} pole${quali.poles === 1 ? '' : 's'}`)
  return parts.join(' · ')
}

/** Career chips + per-series all-time and per-season lines, shared by the
 * driver and team modals so the two read identically. */
export default function CareerStats({ stats }: { stats: CareerStatsData }) {
  if (stats.career.starts === 0) return null
  const chips: { label: string; value: number }[] = [
    { label: 'Starts', value: stats.career.starts },
    { label: 'Wins', value: stats.career.wins },
    { label: 'Podiums', value: stats.career.podiums },
    { label: 'Top 5s', value: stats.career.top5s },
    { label: 'Poles', value: stats.career.poles },
    { label: 'DNFs', value: stats.career.dnfs },
  ]
  // A per-series all-time line only earns its place once the series spans more
  // than one season here; otherwise it restates the single season line below.
  const multiSeason = new Set(
    stats.bySeries
      .filter((s) => stats.seasons.filter((x) => x.seriesName === s.seriesName).length > 1)
      .map((s) => s.seriesId),
  )
  return (
    <section className="dm-stats" aria-label="Career stats">
      <dl className="dm-stat-chips">
        {chips.map((c) => (
          <div key={c.label} className="dm-stat-chip">
            <dd className="num">{c.value}</dd>
            <dt>{c.label}</dt>
          </div>
        ))}
      </dl>
      <ul className="dm-stat-lines">
        {stats.bySeries
          .filter((s) => multiSeason.has(s.seriesId))
          .map((s) => (
            <li key={`sr-${s.seriesId}`}>
              <span className="dm-stat-when">{s.seriesName} all-time</span>
              <span className="dm-stat-what">{formatSplit(s.byFormat, s.quali)}</span>
            </li>
          ))}
        {stats.seasons.map((s) => (
          <li key={`se-${s.seasonId}-${s.className}`}>
            <span className="dm-stat-when">
              {s.year} {s.seriesName}
            </span>
            <span className="dm-stat-what">{formatSplit(s.byFormat, s.quali)}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
