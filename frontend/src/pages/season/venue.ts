// Client-side mirror of SheetController.venueAbbrev — used where the payload
// carries raw event/circuit names (hub calendar) rather than server-computed
// venue codes. Keep the two lists in sync.
export function venueOf(eventName: string | null, circuitName: string | null): string {
  const haystack = `${eventName ?? ''} ${circuitName ?? ''}`.toLowerCase()
  if (haystack.includes('daytona')) return 'DAY'
  if (haystack.includes('sebring')) return 'SEB'
  if (haystack.includes('long beach')) return 'LBH'
  if (haystack.includes('laguna') || haystack.includes('monterey') || haystack.includes('weathertech raceway') || haystack.includes('wrls'))
    return 'LAG'
  if (haystack.includes('detroit')) return 'DET'
  if (haystack.includes('watkins') || haystack.includes('glen')) return 'WGI'
  if (haystack.includes('canadian tire') || haystack.includes('bowmanville') || haystack.includes('ctmp') || haystack.includes('mosport'))
    return 'CTMP'
  if (haystack.includes('road america')) return 'RDA'
  if (haystack.includes('virginia')) return 'VIR'
  if (haystack.includes('indianapolis')) return 'IMS'
  if (haystack.includes('road atlanta') || haystack.includes('michelin raceway')) return 'ATL'
  if (haystack.includes('cota') || haystack.includes('circuit of the americas')) return 'COTA'
  const base = circuitName ?? eventName ?? '???'
  return base
    .replace(/[^A-Za-z]/g, '')
    .toUpperCase()
    .slice(0, 3)
}
