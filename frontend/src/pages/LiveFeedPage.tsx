import { useEffect, useState } from 'react'

/** `GET /api/live/feed-championships`. series* are null when the name stands for no series. */
interface FeedChampionship {
  champDbId: number | null
  champName: string
  seriesId: number | null
  seriesName: string | null
  weekends: number
  unfiledWeekends: number
  lastSeen: string
}

interface SeriesOption {
  id: number
  name: string
}

/**
 * The championships the live feed has carried, and which series each stands
 * for. A championship whose name is a series' name (or alias) is filed
 * automatically; mapping one here adds that alias and files its weekends.
 * See docs/LIVE_TIMING_ALL_SERIES_PLAN.md, slice 3.
 */
export default function LiveFeedPage() {
  const [rows, setRows] = useState<FeedChampionship[] | null>(null)
  const [series, setSeries] = useState<SeriesOption[]>([])
  const [choice, setChoice] = useState<Record<string, number>>({})
  const [message, setMessage] = useState<{ tone: 'error' | 'ok'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  async function load() {
    const [c, s] = await Promise.all([fetch('/api/live/feed-championships'), fetch('/api/series')])
    if (c.ok) setRows(await c.json())
    else setMessage({ tone: 'error', text: `Could not load the feed's championships (${c.status})` })
    if (s.ok) setSeries(((await s.json()) as SeriesOption[]).sort((a, b) => a.name.localeCompare(b.name)))
  }

  useEffect(() => {
    void load()
  }, [])

  async function map(row: FeedChampionship) {
    const seriesId = choice[row.champName]
    if (!seriesId) return
    setBusy(true)
    setMessage(null)
    const res = await fetch('/api/live/feed-championships/map', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ champName: row.champName, seriesId }),
    })
    const body = await res.json().catch(() => null)
    if (res.ok) {
      const filed = body?.weekendsFiled ?? 0
      setMessage({
        tone: 'ok',
        text: `${row.champName} now stands for ${body?.championship?.seriesName ?? 'that series'}. ${
          filed === 1 ? '1 weekend filed.' : `${filed} weekends filed.`
        }`,
      })
    } else {
      setMessage({ tone: 'error', text: body?.message ?? `Mapping failed (${res.status})` })
    }
    await load()
    setBusy(false)
  }

  return (
    <section className="live-feed-page">
      <h2>Live timing</h2>
      <p>
        Every championship the live feed has carried. Sessions are filed under a Pit Pass event by championship: a
        championship named like a series (or one of its aliases) is filed on its own, once its cars match that
        weekend&apos;s entry list. Map the others to a series here.
      </p>
      {message && (
        <p className={message.tone === 'error' ? 'error' : 'muted'} role={message.tone === 'error' ? 'alert' : 'status'}>
          {message.text}
        </p>
      )}

      {rows == null ? (
        <p className="muted" role="status">
          Loading…
        </p>
      ) : rows.length === 0 ? (
        <p className="muted">The feed has not carried any championship yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Championship (feed)</th>
              <th>Pit Pass series</th>
              <th>Weekends</th>
              <th>Last seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.champName}>
                <td>{r.champName}</td>
                <td>
                  {r.seriesName ?? (
                    <span className="live-feed-map">
                      <select
                        aria-label={`Series for ${r.champName}`}
                        value={choice[r.champName] ?? ''}
                        onChange={(e) => setChoice({ ...choice, [r.champName]: Number(e.target.value) })}
                      >
                        <option value="">Not in Pit Pass</option>
                        {series.map((s) => (
                          <option key={s.id} value={s.id}>
                            {s.name}
                          </option>
                        ))}
                      </select>{' '}
                      <button type="button" disabled={busy || !choice[r.champName]} onClick={() => void map(r)}>
                        Map
                      </button>
                    </span>
                  )}
                </td>
                <td>
                  {r.weekends}
                  {r.unfiledWeekends > 0 && <span className="muted"> · {r.unfiledWeekends} not filed</span>}
                </td>
                <td>{new Date(r.lastSeen).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
