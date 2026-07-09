import { useEffect, useState } from 'react'

interface EventSummary {
  id: number
  name: string
  circuitName: string | null
  eventDate: string | null
  year: number
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
}

interface EventDetail {
  event: EventSummary
  entries: EventEntry[]
}

function ordinal(n: number): string {
  const suffix = n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] ?? 'th'
  return `${n}${suffix}`
}

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[]>([])
  const [detail, setDetail] = useState<EventDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch('/api/events')
      .then((r) => {
        if (!r.ok) throw new Error(`Backend returned ${r.status}`)
        return r.json()
      })
      .then(setEvents)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [])

  async function open(id: number) {
    const res = await fetch(`/api/events/${id}`)
    if (res.ok) setDetail(await res.json())
  }

  if (detail) {
    const classes = [...new Set(detail.entries.map((e) => e.className))]
    return (
      <section>
        <button onClick={() => setDetail(null)}>← All events</button>
        <h2>
          {detail.event.name} <small>({detail.event.circuitName})</small>
        </h2>
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
                        <img
                          className="entry-thumb"
                          src={`/api/entries/${e.entryId}/image`}
                          alt=""
                          loading="lazy"
                          onError={(ev) => {
                            ev.currentTarget.style.display = 'none'
                          }}
                        />
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

  return (
    <section>
      {error && <p className="error">{error}</p>}
      {events.length === 0 ? (
        <p>No events yet — import a results file first.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Event</th>
              <th>Circuit</th>
              <th>Series</th>
              <th>Entries</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id} className="clickable" onClick={() => open(e.id)}>
                <td>{e.eventDate}</td>
                <td>{e.name}</td>
                <td>{e.circuitName}</td>
                <td>
                  {e.seriesName} {e.year}
                </td>
                <td>{e.entryCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
