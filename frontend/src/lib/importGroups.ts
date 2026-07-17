/**
 * Helpers for reading a staged import batch's identity out of its filename and
 * summary — shared by the iRacing result view and the confirm-import step. A
 * subsession's several batches (results + grid) share one filename, and every
 * summary reads "<circuit/championship> — <detail>".
 */

// "subsession-80968360.json" → "80968360", the key that groups a round's batches.
export function subsessionOf(filename: string): string {
  return filename.replace(/^subsession-/, '').replace(/\.json$/, '')
}

// The circuit / championship name a batch summary leads with, before the " — ".
export function groupName(summary: string | null): string {
  return summary?.split(' — ')[0] ?? 'Import'
}

// The half of the summary after the circuit — "Qualifying, 30 classified entries".
export function batchDetail(summary: string | null): string {
  const i = summary?.indexOf(' — ') ?? -1
  return i >= 0 ? (summary as string).slice(i + 3) : (summary ?? '')
}

// A staged import's event date, shown "22 Jan 2026"; falls back to the year.
export function formatEventDate(iso: string | null, year: number): string {
  if (!iso) return String(year)
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? String(year)
    : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

// Display label for a batch kind, across the import surfaces.
export const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  ENTRY_LIST: 'Entry list',
  STANDINGS: 'Standings',
  GRID: 'Grid',
  FLAGS: 'Flags',
}
