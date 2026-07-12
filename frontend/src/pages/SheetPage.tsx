import { useEffect, useState, type CSSProperties, type MouseEvent } from 'react'
import 'flag-icons/css/flag-icons.min.css'
import './sheet.css'
import { flagCode } from '../lib/countries'
import { finishBucket, finishText, type FormRace } from '../lib/raceForm'
import TeamSheetsModal, { prefetchTeamSheets } from '../components/TeamSheetsModal'

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

/** Light translucent tint of the class colour, for zebra rows. */
function tint(hex: string, alpha: number): string {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const n = parseInt(full, 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
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

export default function SheetPage({ eventId }: { eventId: number }) {
  const [sheet, setSheet] = useState<Sheet | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [teamSheet, setTeamSheet] = useState<{ page: number; title: string } | null>(null)

  useEffect(() => {
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

  if (error) return <p className="error">{error}</p>
  if (!sheet) return <p>Loading…</p>

  return (
    <div className="sheet">
      <div className="sheet-toolbar no-print">
        <span>
          Prior-year cells are editable — click, type, and they save automatically. Print with the
          button (choose “Save as PDF”).
        </span>
        <button onClick={() => window.print()}>Print / Save PDF</button>
      </div>

      <header className="sheet-header">
        <h1>{sheet.eventName}</h1>
        <p>
          {sheet.seriesName} {sheet.year}
          {sheet.roundOrdinal && <> — Round {sheet.roundOrdinal}</>}
          {sheet.circuitName && <> — {sheet.circuitName}</>}
          {sheet.eventDate && <> — {sheet.eventDate}</>}
        </p>
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
          style={
            {
              '--class-color': cls.color,
              '--class-tint': tint(cls.color, 0.07),
            } as CSSProperties
          }
        >
          <h2>{cls.className}</h2>
          <table>
            <thead>
              <tr>
                <th className="col-num">#</th>
                <th className="col-team">Team</th>
                <th className="col-mfr">Mfr</th>
                <th className="col-drivers">Drivers</th>
                <th className="col-q">Start Pos</th>
                <th className="col-prior">{sheet.priorYearLabel}</th>
                <th className="col-champ">{sheet.year} Champ</th>
                <th className="col-photo"></th>
              </tr>
            </thead>
            {/* One tbody per entry: keeps the main row and its form strip
                zebra-tinted and page-broken as a unit. */}
            {cls.entries.map((e) => (
              <tbody
                key={e.entryId}
                className={teamSheetsUrl && e.teamSheetPage != null ? 'row-linked' : undefined}
                title={teamSheetsUrl && e.teamSheetPage != null ? 'Open team sheets' : undefined}
                onClick={(ev) => openTeamSheet(ev, e)}
              >
                <tr>
                  <td className="col-num">{e.carNumber}</td>
                  <td className="col-team">
                    {e.teamName}
                    {e.isGuest && <span className="sheet-badge">GUEST</span>}
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
                            {d.rating ? `(${d.rating}) ` : d.isTbd ? '(?) ' : ''}
                            {d.name}
                          </span>
                        </div>
                      )
                    })}
                  </td>
                  <td className="col-q">{e.qualifying}</td>
                  <td
                    className={e.priorYearAuto ? 'col-prior editable prior-auto' : 'col-prior editable'}
                    title={e.priorYearAuto ? 'Auto from last year (same car & team) — click to override' : undefined}
                    contentEditable
                    suppressContentEditableWarning
                    onBlur={(ev) =>
                      saveNote(e.entryId, ev.currentTarget.textContent ?? '', e.priorYearNote ?? '')
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
                                {races.length === 0
                                  ? '—'
                                  : races.map((r) => (
                                      <span key={r.raceOrdinal} className="form-race">
                                        {rnd.raceCount > 1 && (
                                          <span className="form-race-label">R{r.raceOrdinal}</span>
                                        )}
                                        {r.start != null && (
                                          <>
                                            <span className="form-start">{r.start}</span>
                                            <span className="form-arrow">→</span>
                                          </>
                                        )}
                                        <span className={`form-finish ${finishBucket(r)}`}>
                                          {finishText(r)}
                                        </span>
                                      </span>
                                    ))}
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            ))}
          </table>
        </section>
        )
      })}

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
