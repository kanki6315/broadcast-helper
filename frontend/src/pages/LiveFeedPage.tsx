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
      <ShareLinks />
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

/** A working link from `GET /api/live/share`. Never carries the secret. label is null for a link made before names. */
interface ShareLinkRow {
  id: number
  label: string | null
  createdBy: string | null
  createdAt: string
  lastUsedAt: string | null
}

/**
 * The shareable timing links, one per person, no expiry. Revoking one stops
 * only that person's link; a link's URL is shown only right after it is made.
 */
function ShareLinks() {
  const [links, setLinks] = useState<ShareLinkRow[] | null>(null)
  const [label, setLabel] = useState('')
  const [fresh, setFresh] = useState<{ label: string; url: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  async function load() {
    const res = await fetch('/api/live/share')
    if (res.ok) setLinks(((await res.json()) as { links: ShareLinkRow[] }).links)
    else setProblem(`Could not load the share links (${res.status})`)
  }

  useEffect(() => {
    void load()
  }, [])

  async function make(e: React.FormEvent) {
    e.preventDefault()
    if (!label.trim()) return
    setBusy(true)
    setProblem(null)
    setCopied(false)
    const res = await fetch('/api/live/share', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label: label.trim() }),
    })
    const body = await res.json().catch(() => null)
    if (res.ok) {
      setFresh({ label: body.link.label, url: `${window.location.origin}/#/live/${body.token}` })
      setLabel('')
    } else {
      setProblem(body?.message ?? `Could not make a link (${res.status})`)
    }
    await load()
    setBusy(false)
  }

  async function revoke(link: ShareLinkRow) {
    const who = link.label ?? 'the unnamed link'
    if (!window.confirm(`Revoke ${who}? It stops working for everyone who has it.`)) return
    setBusy(true)
    setProblem(null)
    const res = await fetch(`/api/live/share/${link.id}`, { method: 'DELETE' })
    if (!res.ok) setProblem(`Could not revoke ${who} (${res.status})`)
    await load()
    setBusy(false)
  }

  async function copy() {
    if (!fresh) return
    try {
      await navigator.clipboard.writeText(fresh.url)
      setCopied(true)
    } catch {
      setProblem('Could not copy; select the link and copy it by hand.')
    }
  }

  return (
    <>
      <h2>Share links</h2>
      <p>
        Links that open the timing pages — every series, live and recorded — without signing in, and nothing else of
        Pit Pass. Make one per person: revoking one stops only that person&apos;s, and each link has its own allowance
        of requests, so people watching from one network don&apos;t slow each other down.
      </p>
      {problem && (
        <p className="error" role="alert">
          {problem}
        </p>
      )}
      <form className="users-form" onSubmit={(e) => void make(e)}>
        <input
          value={label}
          placeholder="Who it's for, e.g. Sam (commentary booth)"
          aria-label="Who the link is for"
          maxLength={80}
          disabled={busy}
          onChange={(e) => setLabel(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !label.trim()}>
          Make a link
        </button>
      </form>
      {fresh && (
        <p className="share-link-fresh">
          <span>{fresh.label}&apos;s link:</span>
          <br />
          <input aria-label={`${fresh.label}'s link`} readOnly value={fresh.url} onFocus={(e) => e.target.select()} />{' '}
          <button type="button" onClick={() => void copy()}>
            {copied ? 'Copied' : 'Copy'}
          </button>
          <br />
          <span className="muted">Copy it now: it is shown only this once.</span>
        </p>
      )}
      {links == null ? (
        <p className="muted" role="status">
          Loading…
        </p>
      ) : links.length === 0 ? (
        <p className="muted">No link is working.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>For</th>
              <th>Made</th>
              <th>Last used</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {links.map((l) => (
              <tr key={l.id}>
                <td>{l.label ?? <span className="muted">Unnamed (made before links had names)</span>}</td>
                <td>
                  {new Date(l.createdAt).toLocaleString()}
                  {l.createdBy && <span className="muted"> by {l.createdBy}</span>}
                </td>
                <td>{l.lastUsedAt ? new Date(l.lastUsedAt).toLocaleString() : <span className="muted">Not yet</span>}</td>
                <td>
                  <button type="button" disabled={busy} onClick={() => void revoke(l)}>
                    Revoke
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
