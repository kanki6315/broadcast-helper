import { useCallback, useEffect, useState } from 'react'
import { type CarNumberAlias } from '../../lib/api'
import { useIsAdmin } from '../../lib/auth'

/** Admin-only footnote under a car-number-keyed recap grid: the season's rare
 * "one entrant, two numbers" links (a one-off renumbering like JDC-Miller's
 * #5 running Daytona as #85, or an entry handed to a new organization
 * mid-season). Linking the other number to the standings row's number makes
 * the recap gather every weekend onto that one row; per-event pages keep
 * showing the number as raced. */
export default function CarNumberAliasAdmin({
  seasonId,
  className,
  rowKeys,
  onChanged,
}: {
  seasonId: number
  className: string
  /** The grid's standings keys — the "counts as" candidates. */
  rowKeys: string[]
  onChanged: () => void
}) {
  const isAdmin = useIsAdmin()
  const [open, setOpen] = useState(false)
  const [aliases, setAliases] = useState<CarNumberAlias[]>([])
  const [carNumber, setCarNumber] = useState('')
  const [canonical, setCanonical] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const res = await fetch(`/api/seasons/${seasonId}/car-number-aliases`)
    if (!res.ok) return
    const all = (await res.json()) as CarNumberAlias[]
    setAliases(all.filter((a) => a.className.trim().toLowerCase() === className.trim().toLowerCase()))
  }, [seasonId, className])

  useEffect(() => {
    if (isAdmin) void load()
  }, [isAdmin, load])

  if (!isAdmin) return null

  async function call(input: string, init: RequestInit): Promise<boolean> {
    setBusy(true)
    setError(null)
    const res = await fetch(input, init)
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Request failed (${res.status})`)
    }
    await load()
    setBusy(false)
    if (res.ok) onChanged()
    return res.ok
  }

  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!carNumber.trim() || !canonical.trim()) return
    const ok = await call(`/api/seasons/${seasonId}/car-number-aliases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        className,
        carNumber: carNumber.trim(),
        canonicalNumber: canonical.trim(),
        note: note.trim() || null,
      }),
    })
    if (ok) {
      setCarNumber('')
      setCanonical('')
      setNote('')
    }
  }

  return (
    <div className="alias-admin">
      <button type="button" className="drv-link" aria-expanded={open} onClick={() => setOpen(!open)}>
        Linked numbers{aliases.length > 0 ? ` (${aliases.length})` : ''}
      </button>
      {open && (
        <div className="alias-admin-body">
          <p className="muted">
            When one {className} entrant raced under a second number — a one-off renumbering, or an
            entry handed to a new team mid-season — link that number to the entrant&apos;s standings
            row here. The recap then gathers every weekend onto the one row; event pages keep the
            number as raced.
          </p>
          {error && <p className="error">{error}</p>}
          {aliases.length > 0 && (
            <ul className="alias-admin-list">
              {aliases.map((a) => (
                <li key={a.id}>
                  <span>
                    #{a.carNumber} counts as #{a.canonicalNumber}
                    {a.note && <span className="muted"> — {a.note}</span>}
                  </span>
                  <button
                    type="button"
                    className="btn"
                    aria-label={`Unlink #${a.carNumber}`}
                    disabled={busy}
                    onClick={() =>
                      void call(`/api/seasons/${seasonId}/car-number-aliases/${a.id}`, {
                        method: 'DELETE',
                      })
                    }
                  >
                    ✕
                  </button>
                </li>
              ))}
            </ul>
          )}
          <form className="users-form" onSubmit={(e) => void add(e)}>
            <input
              value={carNumber}
              placeholder="Raced as #"
              aria-label="Car number as raced"
              disabled={busy}
              onChange={(e) => setCarNumber(e.target.value)}
            />
            <select
              value={canonical}
              aria-label="Counts toward standings number"
              disabled={busy}
              onChange={(e) => setCanonical(e.target.value)}
            >
              <option value="">Counts as…</option>
              {rowKeys.map((k) => (
                <option key={k} value={k}>
                  #{k}
                </option>
              ))}
            </select>
            <input
              value={note}
              placeholder="Note (optional), e.g. ran Daytona as #85"
              aria-label="Note"
              disabled={busy}
              onChange={(e) => setNote(e.target.value)}
            />
            <button
              type="submit"
              className="btn btn-primary"
              disabled={busy || !carNumber.trim() || !canonical}
            >
              Link
            </button>
          </form>
        </div>
      )}
    </div>
  )
}
