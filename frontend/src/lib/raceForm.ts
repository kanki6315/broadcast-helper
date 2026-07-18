// One race's start→finish cell, shared by the season reference table and the
// sheet's per-entry form strip.

export interface FormRace {
  raceOrdinal: number
  start: number | null
  finish: number | null
  status: string | null
}

// A car that took no start has no finishing position; the importer nulls it and
// marks the status. Everything classified (including DNFs) carries a position.
export function statusAbbr(status: string | null): string {
  if (!status) return ''
  const s = status.toLowerCase()
  if (s.includes('not start') || s === 'dns' || s === 'dnp') return 'DNS'
  if (s.includes('disqual') || s === 'dsq') return 'DSQ'
  if (
    s === 'dnf' ||
    s.includes('did not finish') ||
    s.includes('retire') ||
    s.includes('crash') ||
    s.includes('accident') ||
    s.includes('mechanical')
  ) {
    return 'DNF'
  }
  return ''
}

// A non-result: the car didn't take a classified finish (DNS / DQ / DNF). These
// carry no meaningful position, so they colour black rather than by bracket.
export function isNonResult(r: FormRace): boolean {
  return r.finish == null || statusAbbr(r.status) !== ''
}

// The finish token shown in a cell: the in-class position, or the status
// abbreviation (DNS/DSQ/DNF) when there's no classified finish. A "·" stands in
// when neither is known (grid imported but no result yet).
export function finishText(r: FormRace): string {
  const abbr = statusAbbr(r.status)
  if (abbr) return abbr
  if (r.finish != null) return String(r.finish)
  return '·'
}

// Result tier for a finishing position — the app-wide .race-line vocabulary
// (the --res-* tokens): win / top-3 / top-5 tints, plain for anything else
// classified, and the inverted DNF chip for any non-result. The single source
// of the 1 / ≤3 / ≤5 thresholds, shared with RaceLine and the stats surfaces.
export function positionTier(finish: number | null, nonResult: boolean): string {
  if (nonResult) return 'res-dnf'
  if (finish === 1) return 'res-win'
  if (finish != null && finish <= 3) return 'res-top3'
  if (finish != null && finish <= 5) return 'res-top5'
  return ''
}

export function finishTier(r: FormRace): string {
  return positionTier(r.finish, isNonResult(r))
}

// Compact tags for the sessions of one round, in source order — the shared
// notation behind both the standings breakdown's earnings lines and the
// recap's start→finish lines, so a Feature is called "F" on every surface.
//
// The tag is the session's own initial (Qualifying → Q, Heat → H, Feature → F,
// Race/Round → R), numbered only where that word repeats inside the round: a
// weekend of Qualifying + four heats + a feature reads Q · H1 H2 H3 H4 · F,
// and the feature is never flattened into "R5", which would name the main
// event after its position in a list.
//
// The number is positional within the round, never the source's own ordinal.
// Carrera Cup Asia calls Zhuhai's two races "Round 3" and "Round 4", but they
// sit under a column headed Rd 2, so they tag R1/R2 and agree with the header.
//
// A round with one session gets no tags at all — the value alone reads fine.
export function sessionTagList(names: string[]): (string | null)[] {
  if (names.length <= 1) return names.map(() => null)
  const initial = (name: string) => {
    const trimmed = (name ?? '').trim()
    if (!trimmed) return 'R'
    return /^qual/i.test(trimmed) ? 'Q' : trimmed.charAt(0).toUpperCase()
  }
  const counts = new Map<string, number>()
  for (const n of names) counts.set(initial(n), (counts.get(initial(n)) ?? 0) + 1)
  const seen = new Map<string, number>()
  return names.map((name) => {
    const i = initial(name)
    const n = (seen.get(i) ?? 0) + 1
    seen.set(i, n)
    return (counts.get(i) ?? 0) > 1 ? `${i}${n}` : i
  })
}

// The tags for a round's races, keyed by race ordinal. Tagging spans the whole
// round — every race the weekend ran — so a competitor who contested only the
// fourth heat still reads "H4"; tagging just their own lines would call it "H".
export function raceTagsByOrdinal(
  races: { ordinal: number; name: string | null }[],
): Map<number, string | null> {
  const list = sessionTagList(races.map((r) => r.name ?? ''))
  return new Map(races.map((r, i) => [r.ordinal, list[i]]))
}

// One race's cell text: start → finish (in class). Start is absent when no grid
// was imported for that race, so the cell falls back to finish only.
export function raceText(r: FormRace): string {
  const finish = r.finish != null ? String(r.finish) : statusAbbr(r.status)
  const start = r.start != null ? String(r.start) : null
  if (start && finish) return `${start}→${finish}`
  return finish || (start ? `${start}→·` : '')
}
