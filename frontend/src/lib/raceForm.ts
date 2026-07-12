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

// Colour bracket for the finish position (see .pos-* in sheet.css): gold/silver/
// bronze for the podium, then widening bands, black for any non-result.
export function finishBucket(r: FormRace): string {
  if (isNonResult(r)) return 'pos-out'
  const p = r.finish as number
  if (p === 1) return 'pos-p1'
  if (p === 2) return 'pos-p2'
  if (p === 3) return 'pos-p3'
  if (p <= 5) return 'pos-p4'
  if (p <= 10) return 'pos-p6'
  if (p <= 20) return 'pos-p11'
  return 'pos-p21'
}

// One race's cell text: start → finish (in class). Start is absent when no grid
// was imported for that race, so the cell falls back to finish only.
export function raceText(r: FormRace): string {
  const finish = r.finish != null ? String(r.finish) : statusAbbr(r.status)
  const start = r.start != null ? String(r.start) : null
  if (start && finish) return `${start}→${finish}`
  return finish || (start ? `${start}→·` : '')
}
