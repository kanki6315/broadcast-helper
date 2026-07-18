import type { RecapRace } from '../lib/api'
import { positionTier } from '../lib/raceForm'

/** Finish-tier class for a recap race, in the season.css result vocabulary. */
export function raceTier(r: RecapRace): string {
  return positionTier(r.finish, r.notFinished)
}

function isDns(r: RecapRace): boolean {
  return (r.status ?? '').toLowerCase().includes('not started')
}

/** One start→finish chip. Shared by the recap grids and the driver modal so
 * both surfaces always speak the same result language.
 *
 * `tag` names which race of a multi-race round this is ("H1", "F"), from
 * `sessionTagList`; a single-race round passes none and the chip stands alone.
 * It sits outside the tinted chip so the result tints stay the width of the
 * numbers they back. */
export default function RaceLine({ r, tag }: { r: RecapRace; tag?: string | null }) {
  const withTag = (chip: React.ReactNode) =>
    tag ? (
      <span className="race-line-row">
        <span className="race-tag" title={r.name ?? undefined}>
          {tag}
        </span>
        {chip}
      </span>
    ) : (
      chip
    )
  if (isDns(r)) {
    return withTag(<span className="race-line muted">DNS</span>)
  }
  // No grid imported → just the finish; a start→finish pair only when both are
  // known (pole renders as P). A known start with no finish yet is "4/–": the
  // grid for a race still to run.
  const startPart =
    r.start === 1 ? (
      <span className="pole" title="Started from pole">
        P
      </span>
    ) : (
      r.start
    )
  return withTag(
    <span className={`race-line ${raceTier(r)}`.trim()} title={r.notFinished ? 'Retired' : undefined}>
      {r.start != null ? (
        <>
          {startPart}/{r.finish ?? '–'}
        </>
      ) : (
        (r.finish ?? '–')
      )}
      {r.notFinished && (
        <>
          <span className="ret" aria-hidden="true">
            R
          </span>
          <span className="sr-only"> retired</span>
        </>
      )}
    </span>,
  )
}
