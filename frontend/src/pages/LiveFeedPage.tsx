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
      <ShareLink />
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

/** `GET /api/live/share`. Never carries the secret. */
interface ShareState {
  link: { id: number; createdBy: string | null; createdAt: string; lastUsedAt: string | null } | null
}

/**
 * The shareable timing link: one at a time, no expiry. Generating a new one
 * stops the old one at once; the URL is shown only right after it is made.
 */
function ShareLink() {
  const [state, setState] = useState<ShareState | null>(null)
  const [fresh, setFresh] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  async function load() {
    const res = await fetch('/api/live/share')
    if (res.ok) setState(await res.json())
  }

  useEffect(() => {
    void load()
  }, [])

  async function generate() {
    if (state?.link && !window.confirm('Make a new link? The current one stops working for everyone who has it.')) return
    setBusy(true)
    setProblem(null)
    setCopied(false)
    const res = await fetch('/api/live/share', { method: 'POST' })
    if (res.ok) {
      const body = await res.json()
      setFresh(`${window.location.origin}/#/live/${body.token}`)
    } else {
      setProblem(`Could not make a link (${res.status})`)
    }
    await load()
    setBusy(false)
  }

  async function revoke() {
    if (!window.confirm('Revoke the link? It stops working for everyone who has it.')) return
    setBusy(true)
    setProblem(null)
    const res = await fetch('/api/live/share', { method: 'DELETE' })
    if (!res.ok) setProblem(`Could not revoke the link (${res.status})`)
    setFresh(null)
    await load()
    setBusy(false)
  }

  async function copy() {
    if (!fresh) return
    try {
      await navigator.clipboard.writeText(fresh)
      setCopied(true)
    } catch {
      setProblem('Could not copy; select the link and copy it by hand.')
    }
  }

  return (
    <>
      <h2>Share link</h2>
      <p>
        A link that opens the timing pages — every series, live and recorded — without signing in, and nothing else of
        Pit Pass. Anyone holding it can watch until it is revoked or replaced.
      </p>
      {problem && (
        <p className="error" role="alert">
          {problem}
        </p>
      )}
      {state == null ? (
        <p className="muted" role="status">
          Loading…
        </p>
      ) : (
        <>
          {fresh ? (
            <p className="share-link-fresh">
              <input aria-label="Share link" readOnly value={fresh} onFocus={(e) => e.target.select()} />{' '}
              <button type="button" onClick={() => void copy()}>
                {copied ? 'Copied' : 'Copy'}
              </button>
              <br />
              <span className="muted">Copy it now: it is shown only this once.</span>
            </p>
          ) : state.link ? (
            <p>
              A link is working: made {new Date(state.link.createdAt).toLocaleString()}
              {state.link.createdBy && ` by ${state.link.createdBy}`},{' '}
              {state.link.lastUsedAt ? `last used ${new Date(state.link.lastUsedAt).toLocaleString()}` : 'not used yet'}.
            </p>
          ) : (
            <p className="muted">No link is working.</p>
          )}
          <p>
            <button type="button" disabled={busy} onClick={() => void generate()}>
              {state.link ? 'Make a new link' : 'Make a link'}
            </button>{' '}
            {state.link && (
              <button type="button" disabled={busy} onClick={() => void revoke()}>
                Revoke
              </button>
            )}
          </p>
        </>
      )}
    </>
  )
}
