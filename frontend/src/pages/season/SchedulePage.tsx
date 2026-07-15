import { useNavigate } from 'react-router-dom'
import { useSeason } from './SeasonLayout'

export default function SchedulePage() {
  const { hub } = useSeason()
  const navigate = useNavigate()

  if (hub.events.length === 0) {
    return (
      <div className="empty-state">
        No events yet — import a results file or entry list from the Imports tab.
      </div>
    )
  }

  const today = new Date().toISOString().slice(0, 10)

  return (
    <div>
      <table>
        <thead>
          <tr>
            <th className="num">Rd</th>
            <th>Event</th>
            <th>Circuit</th>
            <th>Date</th>
            <th className="num">Entries</th>
            <th className="num">Sessions</th>
            <th aria-label="Links" />
          </tr>
        </thead>
        <tbody>
          {hub.events.map((e) => {
            const upcoming = e.eventDate != null && e.eventDate >= today
            return (
              <tr key={e.id} className="clickable" onClick={() => navigate(`/events/${e.id}`)}>
                <td className="num">{e.roundOrdinal ?? '—'}</td>
                <td style={{ fontWeight: 500 }}>
                  {e.name}
                  {upcoming && <span className="badge muted">upcoming</span>}
                </td>
                <td>{e.circuitName}</td>
                <td className="num">{e.eventDate}</td>
                <td className="num">{e.entryCount || '—'}</td>
                <td className="num">{e.sessionCount || '—'}</td>
                <td>
                  <a
                    href={`#/sheet/${e.id}`}
                    target="_blank"
                    rel="noreferrer"
                    onClick={(ev) => ev.stopPropagation()}
                  >
                    Sheet ↗
                  </a>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
        Click a row for the event page (car photos, team-sheets PDF, notes).
      </p>
    </div>
  )
}
