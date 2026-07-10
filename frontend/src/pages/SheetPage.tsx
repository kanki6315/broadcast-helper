import { useEffect, useState } from 'react'
import 'flag-icons/css/flag-icons.min.css'
import './sheet.css'
import { flagCode } from '../lib/countries'

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
  best: string | null
  last: string | null
  priorYearNote: string | null
  priorYearAuto: boolean
  imageVersion: number | null
}

interface SheetClass {
  className: string
  entries: SheetEntry[]
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
  classes: SheetClass[]
}

export default function SheetPage({ eventId }: { eventId: number }) {
  const [sheet, setSheet] = useState<Sheet | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/events/${eventId}/sheet`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setSheet)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load sheet'))
  }, [eventId])

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

      {sheet.classes.map((cls) => (
        <section key={cls.className} className="sheet-class" data-class={cls.className}>
          <h2>{cls.className}</h2>
          <table>
            <thead>
              <tr>
                <th className="col-num">#</th>
                <th className="col-team">Team</th>
                <th className="col-mfr">Mfr</th>
                <th className="col-drivers">Drivers</th>
                <th className="col-q">Q</th>
                <th className="col-prior">{sheet.priorYearLabel}</th>
                <th className="col-champ">{sheet.year} Champ</th>
                <th className="col-best">Best {sheet.year}</th>
                <th className="col-last">Last {sheet.year}</th>
                <th className="col-photo"></th>
              </tr>
            </thead>
            <tbody>
              {cls.entries.map((e) => (
                <tr key={e.entryId}>
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
                  <td className="col-best">{e.best}</td>
                  <td className="col-last">{e.last}</td>
                  <td className="col-photo">
                    {e.imageVersion != null && (
                      <img src={`/api/entries/${e.entryId}/image?variant=sheet&v=${e.imageVersion}`} alt="" />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  )
}
