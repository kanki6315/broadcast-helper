import { useEffect, useState, type ChangeEvent } from 'react'
import { Link, useParams } from 'react-router-dom'

interface EventSummary {
  id: number
  name: string
  circuitName: string | null
  eventDate: string | null
  year: number
  seasonId: number
  seriesName: string
  sessionCount: number
  entryCount: number
}

interface EventEntry {
  entryId: number
  carNumber: string
  className: string
  classGroup: string | null
  teamName: string
  vehicle: string | null
  manufacturer: string | null
  isGuest: boolean
  drivers: string | null
  racePositionOverall: number | null
  racePositionInClass: number | null
  raceStatus: string | null
  imageVersion: number | null
}

interface EventDetail {
  event: EventSummary
  entries: EventEntry[]
}

interface TeamSheetPageMapping {
  carNumber: string
  page: number
  teamName: string | null
}

interface TeamSheets {
  filename: string | null
  uploadedAt: string
  version: number
  pageCount: number | null
  pages: TeamSheetPageMapping[]
}

/** "04" and "4" are the same car across documents; "0" stays "0". */
function normalizeCar(n: string): string {
  return n.trim().replace(/^0+(?=\d)/, '')
}

function ordinal(n: number): string {
  const suffix = n % 100 >= 11 && n % 100 <= 13 ? 'th' : (['th', 'st', 'nd', 'rd'][n % 10] ?? 'th')
  return `${n}${suffix}`
}

export default function EventDetailPage() {
  const { eventId } = useParams()
  const [detail, setDetail] = useState<EventDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/events/${eventId}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setDetail)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [eventId])

  if (error) return <p className="error">{error}</p>
  if (!detail) return <p>Loading…</p>

  const classes = [...new Set(detail.entries.map((e) => e.className))]
  return (
    <section>
      <p>
        <Link to={`/seasons/${detail.event.seasonId}`}>
          ← {detail.event.seriesName} {detail.event.year}
        </Link>
      </p>
      <h2>
        {detail.event.name} <small>({detail.event.circuitName})</small>{' '}
        <a href={`#/sheet/${detail.event.id}`} target="_blank" rel="noreferrer">
          Sheet →
        </a>
      </h2>
      <TeamSheetsSection eventId={detail.event.id} entries={detail.entries} />
      {classes.map((cls) => (
        <div key={cls}>
          <h3>{cls}</h3>
          <table>
            <thead>
              <tr>
                <th></th>
                <th>#</th>
                <th>Team</th>
                <th>Drivers</th>
                <th>Car</th>
                <th>Finish</th>
                <th>Overall</th>
              </tr>
            </thead>
            <tbody>
              {detail.entries
                .filter((e) => e.className === cls)
                .map((e) => (
                  <tr key={e.entryId}>
                    <td>
                      {e.imageVersion != null && (
                        <img
                          className="entry-thumb"
                          src={`/api/entries/${e.entryId}/image?variant=sheet&v=${e.imageVersion}`}
                          alt=""
                          loading="lazy"
                        />
                      )}
                    </td>
                    <td>
                      {e.carNumber}
                      {e.isGuest && <span className="badge">GUEST</span>}
                    </td>
                    <td>{e.teamName}</td>
                    <td>{e.drivers}</td>
                    <td>{e.vehicle}</td>
                    <td>{e.racePositionInClass != null ? ordinal(e.racePositionInClass) : '—'}</td>
                    <td>{e.racePositionOverall != null ? ordinal(e.racePositionOverall) : '—'}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  )
}

/**
 * The event's team-sheets PDF (deep-linked from the sheet's rows): upload,
 * replace, remove, and fix the extracted car -> page mapping when a car is
 * missing or the PDF numbers it differently.
 */
function TeamSheetsSection({ eventId, entries }: { eventId: number; entries: EventEntry[] }) {
  const [sheets, setSheets] = useState<TeamSheets | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/events/${eventId}/team-sheets`)
      .then((r) => (r.ok ? r.json() : r.status === 404 ? null : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setSheets)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load team sheets'))
  }, [eventId])

  async function call(path: string, init: RequestInit): Promise<void> {
    setBusy(true)
    setError(null)
    try {
      const r = await fetch(path, init)
      if (r.status === 404 && init.method === 'DELETE') {
        setSheets(null)
        return
      }
      if (!r.ok) {
        const body = await r.json().catch(() => null)
        throw new Error(body?.message ?? `Backend returned ${r.status}`)
      }
      setSheets(init.method === 'DELETE' ? null : await r.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setBusy(false)
    }
  }

  function upload(ev: ChangeEvent<HTMLInputElement>) {
    const file = ev.target.files?.[0]
    ev.target.value = '' // allow re-selecting the same file
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    void call(`/api/events/${eventId}/team-sheets`, { method: 'POST', body: form })
  }

  function setPage(carNumber: string, page: number | null) {
    void call(`/api/events/${eventId}/team-sheets/pages`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ carNumber, page }),
    })
  }

  const pageByCar = new Map((sheets?.pages ?? []).map((p) => [normalizeCar(p.carNumber), p]))
  // One row per car (entries repeat a car only across classes, not within).
  const cars = [...new Map(entries.map((e) => [normalizeCar(e.carNumber), e])).values()]
  const unmapped = cars.filter((e) => !pageByCar.has(normalizeCar(e.carNumber)))

  return (
    <div className="team-sheets">
      <h3>Team sheets PDF</h3>
      {error && <p className="error">{error}</p>}
      {!sheets ? (
        <p>
          <label>
            Attach the event's team-sheets PDF (rows on the sheet page will deep-link to it):{' '}
            <input type="file" accept="application/pdf" onChange={upload} disabled={busy} />
          </label>
        </p>
      ) : (
        <>
          <p>
            <strong>{sheets.filename ?? 'team-sheets.pdf'}</strong>
            {' — '}
            {sheets.pageCount != null && <>{sheets.pageCount} pages, </>}
            {cars.length - unmapped.length} of {cars.length} cars mapped
            {' · uploaded '}
            {new Date(sheets.uploadedAt).toLocaleString()}{' '}
            <a href={`/api/events/${eventId}/team-sheets/data?v=${sheets.version}`} target="_blank" rel="noreferrer">
              open
            </a>
          </p>
          {unmapped.length > 0 && (
            <div>
              <p>Not found in the PDF — set a page manually if the team is in there:</p>
              <ul>
                {unmapped.map((e) => (
                  <li key={e.entryId}>
                    #{e.carNumber} {e.teamName}{' '}
                    <PageInput
                      max={sheets.pageCount}
                      disabled={busy}
                      onSet={(page) => setPage(e.carNumber, page)}
                    />
                  </li>
                ))}
              </ul>
            </div>
          )}
          <details>
            <summary>Extracted mapping ({sheets.pages.length} cars)</summary>
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>Team (per PDF)</th>
                  <th>Page</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {sheets.pages.map((p) => (
                  <tr key={p.carNumber}>
                    <td>{p.carNumber}</td>
                    <td>{p.teamName}</td>
                    <td>
                      <PageInput
                        key={`${p.carNumber}:${p.page}`}
                        value={p.page}
                        max={sheets.pageCount}
                        disabled={busy}
                        onSet={(page) => setPage(p.carNumber, page)}
                      />
                    </td>
                    <td>
                      <button onClick={() => setPage(p.carNumber, null)} disabled={busy}>
                        Clear
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
          <p>
            <label>
              Replace PDF: <input type="file" accept="application/pdf" onChange={upload} disabled={busy} />
            </label>{' '}
            <button
              disabled={busy}
              onClick={() => void call(`/api/events/${eventId}/team-sheets`, { method: 'DELETE' })}
            >
              Remove
            </button>
          </p>
        </>
      )}
    </div>
  )
}

/** Small page-number editor: type a page, commit with Enter or the Set button. */
function PageInput({
  value,
  max,
  disabled,
  onSet,
}: {
  value?: number
  max: number | null
  disabled: boolean
  onSet: (page: number) => void
}) {
  const [text, setText] = useState(value != null ? String(value) : '')

  function commit() {
    const page = Number(text)
    if (!Number.isInteger(page) || page < 1 || (max != null && page > max)) return
    if (page === value) return
    onSet(page)
  }

  return (
    <>
      <input
        type="number"
        min={1}
        max={max ?? undefined}
        value={text}
        style={{ width: '4.5em' }}
        disabled={disabled}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
        }}
      />{' '}
      <button onClick={commit} disabled={disabled}>
        Set
      </button>
    </>
  )
}
