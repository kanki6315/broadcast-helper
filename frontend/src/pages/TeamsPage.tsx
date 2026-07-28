import { useCallback, useEffect, useState } from 'react'

interface AliasRow {
  id: number
  alias: string
}

interface ManagedTeam {
  id: number
  name: string
  aliases: AliasRow[]
  predecessorId: number | null
  predecessorName: string | null
  entryCount: number
  lastYear: number | null
}

/**
 * Curation of the global team catalogue. Importers auto-create a team per new
 * spelling, so the recurring jobs here are: add an alias (a sponsorship-era
 * spelling of the same organization), merge a duplicate the importer minted
 * before the alias existed, and record lineage (an entry transferred to a
 * genuinely new organization — linked, never merged). Raw entry team names are
 * never rewritten; only what they resolve to changes.
 */
export default function TeamsPage() {
  const [q, setQ] = useState('')
  const [teams, setTeams] = useState<ManagedTeam[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [newAlias, setNewAlias] = useState('')
  const [rename, setRename] = useState('')
  const [mergeSource, setMergeSource] = useState('')
  const [predecessor, setPredecessor] = useState('')

  const load = useCallback(async (query: string) => {
    const res = await fetch(`/api/teams/manage?q=${encodeURIComponent(query)}`)
    if (res.ok) setTeams(await res.json())
  }, [])

  useEffect(() => {
    const t = setTimeout(() => void load(q), 200)
    return () => clearTimeout(t)
  }, [q, load])

  const selected = teams.find((t) => t.id === selectedId) ?? null

  async function call(input: string, init: RequestInit): Promise<boolean> {
    setBusy(true)
    setError(null)
    const res = await fetch(input, init)
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Request failed (${res.status})`)
    }
    await load(q)
    setBusy(false)
    return res.ok
  }

  function jsonInit(method: string, body: unknown): RequestInit {
    return {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }
  }

  /** Resolve a picker's typed name against the backend (exact, case-insensitive). */
  async function findTeamByName(name: string): Promise<ManagedTeam | null> {
    const res = await fetch(`/api/teams/manage?q=${encodeURIComponent(name.trim())}`)
    if (!res.ok) return null
    const hits = (await res.json()) as ManagedTeam[]
    return (
      hits.find(
        (t) =>
          t.name.trim().toLowerCase() === name.trim().toLowerCase() ||
          t.aliases.some((a) => a.alias.trim().toLowerCase() === name.trim().toLowerCase()),
      ) ?? null
    )
  }

  async function addAlias(e: React.FormEvent) {
    e.preventDefault()
    if (!selected || !newAlias.trim()) return
    const ok = await call(`/api/teams/${selected.id}/aliases`, jsonInit('POST', { alias: newAlias.trim() }))
    if (ok) setNewAlias('')
  }

  function removeAlias(aliasId: number) {
    if (!selected) return
    void call(`/api/teams/${selected.id}/aliases/${aliasId}`, { method: 'DELETE' })
  }

  async function doRename(e: React.FormEvent) {
    e.preventDefault()
    if (!selected || !rename.trim()) return
    const ok = await call(`/api/teams/${selected.id}`, jsonInit('PATCH', { name: rename.trim() }))
    if (ok) setRename('')
  }

  async function doMerge(e: React.FormEvent) {
    e.preventDefault()
    if (!selected || !mergeSource.trim()) return
    const source = await findTeamByName(mergeSource)
    if (!source) {
      setError(`No team named "${mergeSource.trim()}"`)
      return
    }
    if (
      !window.confirm(
        `Merge "${source.name}" (${source.entryCount} entries) into "${selected.name}"? ` +
          'Its aliases and entries move over and the duplicate is deleted.',
      )
    ) {
      return
    }
    const ok = await call(`/api/teams/${selected.id}/merge`, jsonInit('POST', { sourceTeamId: source.id }))
    if (ok) setMergeSource('')
  }

  async function setPred(e: React.FormEvent) {
    e.preventDefault()
    if (!selected || !predecessor.trim()) return
    const pred = await findTeamByName(predecessor)
    if (!pred) {
      setError(`No team named "${predecessor.trim()}"`)
      return
    }
    const ok = await call(`/api/teams/${selected.id}`, jsonInit('PATCH', { predecessorId: pred.id }))
    if (ok) setPredecessor('')
  }

  function clearPred() {
    if (!selected) return
    void call(`/api/teams/${selected.id}`, jsonInit('PATCH', { clearPredecessor: true }))
  }

  return (
    <section className="users-page">
      <h2>Teams</h2>
      <p>
        Every distinct team spelling the importers have seen becomes a team here. Use{' '}
        <strong>aliases</strong> for sponsorship-era names of the same organization,{' '}
        <strong>merge</strong> for duplicates, and a <strong>predecessor link</strong> when an
        entry transferred to a genuinely new team — its history stays separate but connected.
      </p>
      {error && <p className="error">{error}</p>}

      <form className="users-form" onSubmit={(e) => e.preventDefault()}>
        <input
          type="search"
          value={q}
          placeholder="Search teams and aliases"
          aria-label="Search teams"
          onChange={(e) => setQ(e.target.value)}
        />
      </form>

      <table>
        <thead>
          <tr>
            <th scope="col">Team</th>
            <th scope="col">Aliases</th>
            <th scope="col">Entries</th>
            <th scope="col">Last year</th>
            <th scope="col">Lineage</th>
          </tr>
        </thead>
        <tbody>
          {teams.map((t) => (
            <tr
              key={t.id}
              className={t.id === selectedId ? 'active' : undefined}
              aria-selected={t.id === selectedId}
            >
              <td>
                <button type="button" className="drv-link" onClick={() => setSelectedId(t.id)}>
                  {t.name}
                </button>
              </td>
              <td>
                {t.aliases
                  .filter((a) => a.alias.trim().toLowerCase() !== t.name.trim().toLowerCase())
                  .map((a) => a.alias)
                  .join(' · ') || <span className="muted">—</span>}
              </td>
              <td>{t.entryCount}</td>
              <td>{t.lastYear ?? <span className="muted">—</span>}</td>
              <td>
                {t.predecessorName ? (
                  <>from {t.predecessorName}</>
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
            </tr>
          ))}
          {teams.length === 0 && (
            <tr>
              <td colSpan={5} className="muted">
                No teams match.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {selected && (
        <>
          <h3>{selected.name}</h3>

          <h4>Aliases</h4>
          <ul className="team-alias-list">
            {selected.aliases.map((a) => (
              <li key={a.id}>
                {a.alias}
                <button
                  type="button"
                  className="btn"
                  aria-label={`Remove alias ${a.alias}`}
                  disabled={busy || selected.aliases.length === 1}
                  onClick={() => removeAlias(a.id)}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
          <form className="users-form" onSubmit={(e) => void addAlias(e)}>
            <input
              value={newAlias}
              placeholder="Add a spelling, e.g. Vasser Sullivan with Driehaus"
              aria-label={`New alias for ${selected.name}`}
              disabled={busy}
              onChange={(e) => setNewAlias(e.target.value)}
            />
            <button type="submit" className="btn btn-primary" disabled={busy || !newAlias.trim()}>
              Add alias
            </button>
          </form>

          <h4>Rename</h4>
          <form className="users-form" onSubmit={(e) => void doRename(e)}>
            <input
              value={rename}
              placeholder={selected.name}
              aria-label={`New display name for ${selected.name}`}
              disabled={busy}
              onChange={(e) => setRename(e.target.value)}
            />
            <button type="submit" className="btn" disabled={busy || !rename.trim()}>
              Rename
            </button>
          </form>

          <h4>Merge a duplicate into this team</h4>
          <form className="users-form" onSubmit={(e) => void doMerge(e)}>
            <input
              value={mergeSource}
              placeholder="Duplicate team's name"
              aria-label={`Team to merge into ${selected.name}`}
              disabled={busy}
              list="manage-team-names"
              onChange={(e) => setMergeSource(e.target.value)}
            />
            <button type="submit" className="btn" disabled={busy || !mergeSource.trim()}>
              Merge
            </button>
          </form>

          <h4>Lineage</h4>
          {selected.predecessorName && (
            <p>
              Continued from <strong>{selected.predecessorName}</strong>{' '}
              <button type="button" className="btn" disabled={busy} onClick={clearPred}>
                Clear
              </button>
            </p>
          )}
          <form className="users-form" onSubmit={(e) => void setPred(e)}>
            <input
              value={predecessor}
              placeholder="Predecessor team's name"
              aria-label={`Predecessor for ${selected.name}`}
              disabled={busy}
              list="manage-team-names"
              onChange={(e) => setPredecessor(e.target.value)}
            />
            <button type="submit" className="btn" disabled={busy || !predecessor.trim()}>
              Set predecessor
            </button>
          </form>

          <datalist id="manage-team-names">
            {teams
              .filter((t) => t.id !== selected.id)
              .map((t) => (
                <option key={t.id} value={t.name} />
              ))}
          </datalist>
        </>
      )}
    </section>
  )
}
