import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import SeasonImages from '../components/SeasonImages'

interface CalendarEvent {
  id: number
  name: string
  circuitName: string | null
  eventDate: string | null
  roundOrdinal: number | null
  entryCount: number
  sessionCount: number
}

interface Championship {
  id: number
  title: string
  groupTitle: string | null
  className: string | null
  kind: string | null
  rowCount: number
}

interface Hub {
  id: number
  year: number
  seriesName: string
  events: CalendarEvent[]
  championships: Championship[]
}

export default function SeasonHubPage() {
  const { seasonId } = useParams()
  const navigate = useNavigate()
  const [hub, setHub] = useState<Hub | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/seasons/${seasonId}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setHub)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [seasonId])

  if (error) return <p className="error">{error}</p>
  if (!hub) return <p>Loading…</p>

  const groups = [...new Set(hub.championships.map((c) => c.groupTitle ?? c.title))]

  return (
    <section>
      <p>
        <Link to="/">← All seasons</Link>
      </p>
      <h2>
        {hub.seriesName} {hub.year}
      </h2>

      <h3>Calendar</h3>
      {hub.events.length === 0 ? (
        <p>No events yet — import a results file or entry list.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Rd</th>
              <th>Event</th>
              <th>Circuit</th>
              <th>Date</th>
              <th>Entries</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {hub.events.map((e) => (
              <tr key={e.id} className="clickable" onClick={() => navigate(`/events/${e.id}`)}>
                <td>{e.roundOrdinal ?? '—'}</td>
                <td>{e.name}</td>
                <td>{e.circuitName}</td>
                <td>{e.eventDate}</td>
                <td>{e.entryCount}</td>
                <td>
                  <a
                    href={`#/sheet/${e.id}`}
                    target="_blank"
                    rel="noreferrer"
                    onClick={(ev) => ev.stopPropagation()}
                  >
                    Sheet →
                  </a>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>Championships</h3>
      {hub.championships.length === 0 ? (
        <p>No standings yet — import a standings file.</p>
      ) : (
        groups.map((group) => (
          <div key={group}>
            <h4>{group}</h4>
            <table>
              <thead>
                <tr>
                  <th>Class</th>
                  <th>Type</th>
                  <th>Competitors</th>
                </tr>
              </thead>
              <tbody>
                {hub.championships
                  .filter((c) => (c.groupTitle ?? c.title) === group)
                  .map((c) => (
                    <tr
                      key={c.id}
                      className="clickable"
                      onClick={() => navigate(`/championships/${c.id}`)}
                    >
                      <td>{c.className}</td>
                      <td>{c.kind}</td>
                      <td>{c.rowCount}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        ))
      )}

      <h3>Car images</h3>
      <SeasonImages seasonId={Number(seasonId)} />
    </section>
  )
}
