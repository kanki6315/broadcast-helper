import { useEffect, useState } from 'react'
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
                          src={`/api/entries/${e.entryId}/image?v=${e.imageVersion}`}
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
