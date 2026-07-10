// Maps the 3-letter (ISO 3166-1 alpha-3) country codes IMSA timing files use
// to the 2-letter (alpha-2) codes flag-icons keys on. Covers every code seen
// in the 2026 data plus common motorsport nationalities for future events.
const ALPHA3_TO_ALPHA2: Record<string, string> = {
  ARG: 'ar', AUS: 'au', AUT: 'at', BEL: 'be', BGR: 'bg', BHR: 'bh', BRA: 'br',
  CAN: 'ca', CHE: 'ch', CHL: 'cl', CHN: 'cn', COL: 'co', CRI: 'cr', CYM: 'ky',
  CZE: 'cz', DEU: 'de', DNK: 'dk', EGY: 'eg', ESP: 'es', EST: 'ee', FIN: 'fi',
  FRA: 'fr', GBR: 'gb', GRC: 'gr', HKG: 'hk', HRV: 'hr', HUN: 'hu', IDN: 'id',
  IND: 'in', IRL: 'ie', ISL: 'is', ISR: 'il', ITA: 'it', JPN: 'jp', KOR: 'kr',
  LUX: 'lu', MCO: 'mc', MEX: 'mx', MYS: 'my', NLD: 'nl', NOR: 'no', NZL: 'nz',
  PHL: 'ph', POL: 'pl', PRT: 'pt', QAT: 'qa', ROU: 'ro', RUS: 'ru', SAU: 'sa',
  SGP: 'sg', SRB: 'rs', SVK: 'sk', SVN: 'si', SWE: 'se', THA: 'th', TUR: 'tr',
  TWN: 'tw', UKR: 'ua', URY: 'uy', USA: 'us', VEN: 've', ZAF: 'za', ZWE: 'zw',
}

/** Returns the flag-icons alpha-2 code for a 3-letter nationality, or null. */
export function flagCode(alpha3: string | null | undefined): string | null {
  if (!alpha3) return null
  return ALPHA3_TO_ALPHA2[alpha3.toUpperCase()] ?? null
}
