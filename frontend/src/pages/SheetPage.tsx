import { useEffect, useState, type CSSProperties, type MouseEvent } from 'react'
import 'flag-icons/css/flag-icons.min.css'
import './sheet.css'
import { useIsAdmin } from '../lib/auth'
import { flagCode } from '../lib/countries'
import { finishText, finishTier, statusAbbr, type FormRace } from '../lib/raceForm'
import TeamSheetsModal, { prefetchTeamSheets } from '../components/TeamSheetsModal'
import ScratchpadModal from '../components/ScratchpadModal'
import { useInfoModal } from '../components/infoModal'

interface SheetDriver {
  name: string
  rating: string | null
  isTbd: boolean
  nationality: string | null
}

interface SheetEntry {
  entryId: number
  carNumber: string
  teamName: string
  vehicle: string | null
  manufacturer: string | null
  manufacturerLogoVersion: number | null
  isGuest: boolean
  drivers: SheetDriver[]
  qualifying: string | null
  /** Short-form crew member taking the start ("H. Grisham"); null for solo
   *  series and grids imported before attribution existed. */
  startingDriver: string | null
  championship: string | null
  form: Record<string, FormRace[]>
  priorYearNote: string | null
  priorYearAuto: boolean
  imageVersion: number | null
  teamSheetPage: number | null
}

interface SheetClass {
  className: string
  color: string
  entries: SheetEntry[]
}

interface FormRound {
  ordinal: number
  venue: string
  raceCount: number
}

interface Sheet {
  eventId: number
  eventName: string
  circuitName: string | null
  eventDate: string | null
  year: number
  roundOrdinal: number | null
  seriesName: string
  championshipLabel: string
  priorYearLabel: string
  teamSheetsVersion: number | null
  formRounds: FormRound[]
  classes: SheetClass[]
}

/** One race in the form strip, in the recap's .race-line vocabulary. */
function StripRace({ race, showLabel }: { race: FormRace; showLabel: boolean }) {
  const abbr = statusAbbr(race.status)
  // DNS and not-yet-run races are quiet marks, same as the recap — the loud
  // inverted chip is reserved for retirements/disqualifications.
  const quiet = abbr === 'DNS' || (race.finish == null && abbr === '')
  const start =
    race.start == null ? null : race.start === 1 ? (
      <span className="pole" title="Started from pole">
        P
      </span>
    ) : (
      race.start
    )
  return (
    <span className="form-race">
      {showLabel && <span className="form-race-label">R{race.raceOrdinal}</span>}
      {quiet ? (
        <span className="race-line muted">{finishText(race)}</span>
      ) : (
        <span className={`race-line ${finishTier(race)}`.trim()}>
          {start != null ? (
            <>
              {start}/{finishText(race)}
            </>
          ) : (
            finishText(race)
          )}
        </span>
      )}
    </span>
  )
}

export default function SheetPage({ eventId }: { eventId: number }) {
  const { openDriverByName } = useInfoModal()
  const isAdmin = useIsAdmin()
  const [sheet, setSheet] = useState<Sheet | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [teamSheet, setTeamSheet] = useState<{ page: number; title: string } | null>(null)
  const [scratchpadOpen, setScratchpadOpen] = useState(false)

  useEffect(() => {
    // Reset before fetching: a stale error (or sheet) from a previous eventId
    // must not outlive the navigation that replaced it.
    setSheet(null)
    setError(null)
    void fetch(`/api/events/${eventId}/sheet`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setSheet)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load sheet'))
  }, [eventId])

  const teamSheetsUrl =
    sheet?.teamSheetsVersion != null
      ? `/api/events/${eventId}/team-sheets/data?v=${sheet.teamSheetsVersion}`
      : null

  // Warm the PDF while the broadcaster reads the sheet, so the first row
  // click opens instantly.
  useEffect(() => {
    if (teamSheetsUrl) prefetchTeamSheets(teamSheetsUrl)
  }, [teamSheetsUrl])

  function openTeamSheet(ev: MouseEvent, entry: SheetEntry) {
    // The prior-year cell is contentEditable; clicks there are edits, not
    // navigation.
    if ((ev.target as HTMLElement).closest('[contenteditable]')) return
    if (entry.teamSheetPage == null) return
    setTeamSheet({ page: entry.teamSheetPage, title: `#${entry.carNumber} ${entry.teamName}` })
  }

  async function saveNote(entryId: number, note: string, original: string) {
    // Only persist real edits: clicking through an auto-filled cell must not
    // freeze the computed value into a manual note.
    if (note.trim() === original.trim()) return
    await fetch(`/api/entries/${entryId}/prior-year-note`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note }),
    })
  }

  if (error) {
    return (
      <div className="sheet">
        <div className="error-panel">{error}</div>
      </div>
    )
  }
  if (!sheet) {
    return (
      <div className="sheet">
        <div className="skeleton-block" aria-label="Loading sheet">
          <span className="skeleton" />
          <span className="skeleton" />
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      </div>
    )
  }

  return (
    <div className="sheet">
      <div className="sheet-topbar no-print">
        <a className="sheet-back" href={`#/events/${eventId}`}>
          ← Event
        </a>
        <span className="sheet-hint">
          Prior-year cells are editable — click, type, and they save automatically.
        </span>
        <button className="btn" onClick={() => window.print()}>
          Print / Save PDF
        </button>
      </div>

      <header className="sheet-head">
        <h1>{sheet.eventName}</h1>
        <p className="sheet-meta">
          {sheet.seriesName} {sheet.year}
          {sheet.roundOrdinal != null && <> · Round {sheet.roundOrdinal}</>}
          {sheet.circuitName && <> · {sheet.circuitName}</>}
          {sheet.eventDate && <> · {sheet.eventDate}</>}
        </p>
        <div className="legend sheet-legend no-print" aria-label="Form strip colours">
          <span className="l-win">
            <i /> Win
          </span>
          <span className="l-top3">
            <i /> Top 3
          </span>
          <span className="l-top5">
            <i /> Top 5
          </span>
          <span className="l-dnf">
            <i /> DNF
          </span>
          <span className="l-note">start/finish in class</span>
          <span className="l-pole">P = pole</span>
          <span>· = no result</span>
        </div>
      </header>

      {sheet.classes.map((cls) => {
        // Only rounds this class contested: LMP2 skips IMSA's sprint rounds,
        // and a strip of "—" columns for the whole class is noise.
        const classRounds = sheet.formRounds.filter((rnd) =>
          cls.entries.some((e) => (e.form[rnd.ordinal] ?? []).length > 0),
        )
        return (
          <section
            key={cls.className}
            className="sheet-class"
            data-class={cls.className}
            style={{ '--class-color': cls.color } as CSSProperties}
          >
            <h2 className="sheet-band">{cls.className}</h2>
            <div className="sheet-scroll">
              <table>
                <thead>
                  <tr>
                    <th className="col-num">#</th>
                    <th className="col-team">Team</th>
                    <th className="col-mfr">Mfr</th>
                    <th className="col-drivers">Drivers</th>
                    <th className="col-q">Start</th>
                    <th className="col-prior">{sheet.priorYearLabel}</th>
                    <th className="col-champ">{sheet.year} champ</th>
                    <th className="col-photo"></th>
                  </tr>
                </thead>
                {/* One tbody per entry: keeps the main row and its form strip
                    zebra-tinted and page-broken as a unit. */}
                {cls.entries.map((e) => {
                  const linked = teamSheetsUrl != null && e.teamSheetPage != null
                  return (
                    <tbody
                      key={e.entryId}
                      className={linked ? 'row-linked' : undefined}
                      title={linked ? 'Open team sheets' : undefined}
                      onClick={(ev) => openTeamSheet(ev, e)}
                    >
                      <tr>
                        <td className="col-num">
                          {linked ? (
                            // Activation bubbles to the row's onClick; the
                            // button exists for keyboard reach and AT naming.
                            <button
                              type="button"
                              className="sheet-carlink"
                              aria-label={`Open team sheet for #${e.carNumber} ${e.teamName}`}
                            >
                              {e.carNumber}
                            </button>
                          ) : (
                            e.carNumber
                          )}
                        </td>
                        <td className="col-team">
                          {e.teamName}
                          {e.isGuest && <span className="badge">GUEST</span>}
                        </td>
                        <td className="col-mfr">
                          {e.manufacturerLogoVersion != null ? (
                            <img
                              className="sheet-mfr-logo"
                              src={`/api/manufacturer-logos/${encodeURIComponent(
                                (e.manufacturer ?? '').toLowerCase(),
                              )}/data?v=${e.manufacturerLogoVersion}`}
                              alt={e.manufacturer ?? ''}
                            />
                          ) : (
                            e.manufacturer && <span className="sheet-mfr-name">{e.manufacturer}</span>
                          )}
                        </td>
                        <td className="col-drivers">
                          {e.drivers.map((d, i) => {
                            const flag = flagCode(d.nationality)
                            return (
                              <div key={i} className="sheet-driver">
                                {flag && <span className={`fi fi-${flag}`} title={d.nationality ?? ''} />}
                                <span>
                                  {d.rating ? (
                                    <span className="drv-rating">({d.rating}) </span>
                                  ) : d.isTbd ? (
                                    <span className="drv-rating">(?) </span>
                                  ) : null}
                                  {d.isTbd ? (
                                    d.name
                                  ) : (
                                    // stopPropagation: the row's own click
                                    // opens the team-sheets PDF.
                                    <button
                                      type="button"
                                      className="drv-link"
                                      onClick={(ev) => {
                                        ev.stopPropagation()
                                        openDriverByName(d.name)
                                      }}
                                    >
                                      {d.name}
                                    </button>
                                  )}
                                </span>
                              </div>
                            )
                          })}
                        </td>
                        <td className="col-q">
                          {e.qualifying}
                          {e.startingDriver && <span className="q-driver">{e.startingDriver}</span>}
                        </td>
                        <td
                          className={
                            !isAdmin
                              ? 'col-prior'
                              : e.priorYearAuto
                                ? 'col-prior editable prior-auto'
                                : 'col-prior editable'
                          }
                          title={
                            isAdmin && e.priorYearAuto
                              ? 'Auto from last year (same car & team) — click to override'
                              : undefined
                          }
                          contentEditable={isAdmin}
                          suppressContentEditableWarning
                          onBlur={
                            isAdmin
                              ? (ev) =>
                                  saveNote(e.entryId, ev.currentTarget.textContent ?? '', e.priorYearNote ?? '')
                              : undefined
                          }
                        >
                          {e.priorYearNote}
                        </td>
                        <td className="col-champ">{e.championship}</td>
                        <td className="col-photo">
                          {e.imageVersion != null && (
                            <img src={`/api/entries/${e.entryId}/image?variant=sheet&v=${e.imageVersion}`} alt="" />
                          )}
                        </td>
                      </tr>
                      {classRounds.length > 0 && Object.keys(e.form).length > 0 && (
                        <tr className="form-row">
                          <td colSpan={8}>
                            <div
                              className="form-strip"
                              style={{ gridTemplateColumns: `repeat(${classRounds.length}, minmax(0, 1fr))` }}
                            >
                              {classRounds.map((rnd) => {
                                const races = e.form[rnd.ordinal] ?? []
                                return (
                                  <div key={rnd.ordinal} className="form-round">
                                    <div className="form-round-hd">
                                      R{rnd.ordinal} {rnd.venue}
                                    </div>
                                    <div className="form-round-val">
                                      {races.length === 0 ? (
                                        <span className="race-line muted">—</span>
                                      ) : (
                                        races.map((r) => (
                                          <StripRace
                                            key={r.raceOrdinal}
                                            race={r}
                                            showLabel={rnd.raceCount > 1}
                                          />
                                        ))
                                      )}
                                    </div>
                                  </div>
                                )
                              })}
                            </div>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  )
                })}
              </table>
            </div>
          </section>
        )
      })}

      {/* Floating, not in the topbar: the pad is reached mid-scroll, deep in
          a class table, as often as from the top of the page. */}
      <button className="btn sheet-fab no-print" onClick={() => setScratchpadOpen(true)}>
        Scratchpad
      </button>

      {scratchpadOpen && <ScratchpadModal eventId={eventId} onClose={() => setScratchpadOpen(false)} />}

      {teamSheet && teamSheetsUrl && (
        <TeamSheetsModal
          url={teamSheetsUrl}
          page={teamSheet.page}
          title={teamSheet.title}
          onClose={() => setTeamSheet(null)}
        />
      )}
    </div>
  )
}
