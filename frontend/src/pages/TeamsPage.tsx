import { useCallback, useEffect, useRef, useState } from 'react'
import '../components/combobox.css'

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

const PAGE_SIZE = 100

function manageUrl(query: string, limit: number, offset = 0): string {
  return `/api/teams/manage?q=${encodeURIComponent(query.trim())}&limit=${limit}&offset=${offset}`
}

/** Aliases other than the display name itself, which is always one of them. */
function otherAliases(t: ManagedTeam): string[] {
  const name = t.name.trim().toLowerCase()
  return t.aliases.map((a) => a.alias).filter((a) => a.trim().toLowerCase() !== name)
}

function teamHint(t: ManagedTeam): string {
  const parts = [`${t.entryCount} ${t.entryCount === 1 ? 'entry' : 'entries'}`]
  if (t.lastYear != null) parts.push(`last ${t.lastYear}`)
  return parts.join(' · ')
}

/**
 * Picks a team from the whole catalogue — it searches the backend (names and
 * aliases) on its own, independent of the list's search box, so a duplicate
 * with a divergent spelling is findable without losing the team being edited.
 */
function TeamSearchPicker({
  inputId,
  label,
  placeholder,
  excludeId,
  disabled,
  value,
  onChange,
}: {
  inputId: string
  label: string
  placeholder: string
  excludeId: number
  disabled: boolean
  value: ManagedTeam | null
  onChange: (team: ManagedTeam | null) => void
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [results, setResults] = useState<ManagedTeam[] | null>(null)
  const [active, setActive] = useState(0)

  useEffect(() => {
    if (!open || query.trim() === '') {
      setResults(null)
      return
    }
    let stale = false
    const t = setTimeout(async () => {
      const res = await fetch(manageUrl(query, 20))
      if (!stale && res.ok) {
        setResults(((await res.json()) as ManagedTeam[]).filter((team) => team.id !== excludeId))
        setActive(0)
      }
    }, 200)
    return () => {
      stale = true
      clearTimeout(t)
    }
  }, [query, open, excludeId])

  function pick(team: ManagedTeam) {
    onChange(team)
    setQuery('')
    setOpen(false)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    const n = results?.length ?? 0
    if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && n > 0) {
      e.preventDefault()
      setActive((a) => (a + (e.key === 'ArrowDown' ? 1 : -1) + n) % n)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (open && results?.[active]) pick(results[active])
    } else if (e.key === 'Escape' && open) {
      e.preventDefault()
      setOpen(false)
    }
  }

  const listId = `${inputId}-list`
  return (
    <div className="sep-field">
      <div className={disabled ? 'sep-combo disabled' : 'sep-combo'}>
        <div className="sep-combo-row">
          <input
            id={inputId}
            className="sep-combo-input"
            type="text"
            role="combobox"
            aria-label={label}
            aria-expanded={open && results != null}
            aria-controls={listId}
            aria-activedescendant={open && results?.[active] ? `${inputId}-opt-${active}` : undefined}
            aria-autocomplete="list"
            autoComplete="off"
            disabled={disabled}
            placeholder={value ? value.name : placeholder}
            value={open ? query : value?.name ?? ''}
            onChange={(e) => {
              setQuery(e.target.value)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => window.setTimeout(() => setOpen(false), 120)}
            onKeyDown={onKeyDown}
          />
          {value && !disabled && (
            <button
              type="button"
              className="sep-combo-clear"
              aria-label={`Clear ${label.toLowerCase()}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => onChange(null)}
            >
              ✕
            </button>
          )}
        </div>
        {open && results != null && (
          <ul className="sep-combo-list" id={listId} role="listbox" aria-label={label}>
            {results.length === 0 ? (
              <li className="sep-combo-empty">No other team or alias matches.</li>
            ) : (
              results.map((t, i) => {
                const aliases = otherAliases(t)
                return (
                  <li key={t.id}>
                    <button
                      id={`${inputId}-opt-${i}`}
                      type="button"
                      role="option"
                      aria-selected={i === active}
                      className={`sep-opt team-pick-opt${i === active ? ' active' : ''}`}
                      onMouseEnter={() => setActive(i)}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => pick(t)}
                    >
                      <span className="team-pick-main">
                        <span className="sep-opt-name">{t.name}</span>
                        <span className="sep-opt-hint">{teamHint(t)}</span>
                      </span>
                      {aliases.length > 0 && (
                        <span className="team-pick-aliases">aka {aliases.join(' · ')}</span>
                      )}
                    </button>
                  </li>
                )
              })
            )}
          </ul>
        )}
      </div>
    </div>
  )
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
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  // The editor owns its own copy so it survives the list's search changing.
  const [selected, setSelected] = useState<ManagedTeam | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [newAlias, setNewAlias] = useState('')
  const [rename, setRename] = useState('')
  const [mergeSource, setMergeSource] = useState<ManagedTeam | null>(null)
  const [predecessor, setPredecessor] = useState<ManagedTeam | null>(null)

  // Guards against a slow page for an old query landing after a newer one.
  const requestSeq = useRef(0)
  const scroller = useRef<HTMLDivElement>(null)
  const sentinel = useRef<HTMLDivElement>(null)

  const loadPage = useCallback(async (query: string, offset: number) => {
    const seq = ++requestSeq.current
    setLoading(true)
    try {
      const res = await fetch(manageUrl(query, PAGE_SIZE, offset))
      if (seq !== requestSeq.current || !res.ok) return
      const page = (await res.json()) as ManagedTeam[]
      if (seq !== requestSeq.current) return
      setTeams((prev) => (offset === 0 ? page : [...prev, ...page]))
      setHasMore(page.length === PAGE_SIZE)
      if (offset === 0) scroller.current?.scrollTo({ top: 0 })
    } finally {
      if (seq === requestSeq.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const t = setTimeout(() => void loadPage(q, 0), 200)
    return () => clearTimeout(t)
  }, [q, loadPage])

  const loadMore = useCallback(() => {
    if (!loading && hasMore) void loadPage(q, teams.length)
  }, [loading, hasMore, loadPage, q, teams.length])

  // Scrolling the list pane near its end pulls the next page in.
  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) loadMore()
      },
      { root: scroller.current, rootMargin: '0px 0px 200px 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [loadMore])

  function select(team: ManagedTeam) {
    setSelected(team)
    setNewAlias('')
    setRename('')
    setMergeSource(null)
    setPredecessor(null)
    setError(null)
  }

  /** Re-read the edited team and patch its row in place, so the list keeps
   *  its scroll position and loaded pages. */
  async function refreshSelected(id: number, removedId?: number) {
    const res = await fetch(`/api/teams/manage/${id}`)
    if (!res.ok) return
    const fresh = (await res.json()) as ManagedTeam
    setSelected(fresh)
    setTeams((prev) =>
      prev
        .filter((t) => t.id !== removedId)
        .map((t) => {
          if (t.id === fresh.id) return fresh
          if (t.predecessorId === fresh.id) return { ...t, predecessorName: fresh.name }
          return t
        }),
    )
  }

  async function call(input: string, init: RequestInit, removedId?: number): Promise<boolean> {
    if (!selected) return false
    setBusy(true)
    setError(null)
    const res = await fetch(input, init)
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Request failed (${res.status})`)
    }
    await refreshSelected(selected.id, res.ok ? removedId : undefined)
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
    if (!selected || !mergeSource) return
    if (
      !window.confirm(
        `Merge "${mergeSource.name}" (${mergeSource.entryCount} entries) into "${selected.name}"? ` +
          'Its aliases and entries move over and the duplicate is deleted.',
      )
    ) {
      return
    }
    const ok = await call(
      `/api/teams/${selected.id}/merge`,
      jsonInit('POST', { sourceTeamId: mergeSource.id }),
      mergeSource.id,
    )
    if (ok) setMergeSource(null)
  }

  async function setPred(e: React.FormEvent) {
    e.preventDefault()
    if (!selected || !predecessor) return
    const ok = await call(`/api/teams/${selected.id}`, jsonInit('PATCH', { predecessorId: predecessor.id }))
    if (ok) setPredecessor(null)
  }

  function clearPred() {
    if (!selected) return
    void call(`/api/teams/${selected.id}`, jsonInit('PATCH', { clearPredecessor: true }))
  }

  return (
    <section className="users-page teams-manage-page">
      <h2>Teams</h2>
      <p>
        Every distinct team spelling the importers have seen becomes a team here. Use{' '}
        <strong>aliases</strong> for sponsorship-era names of the same organization,{' '}
        <strong>merge</strong> for duplicates, and a <strong>predecessor link</strong> when an
        entry transferred to a genuinely new team — its history stays separate but connected.
      </p>

      <div className="teams-manage">
        <div className="teams-list-pane">
          <form className="users-form" onSubmit={(e) => e.preventDefault()}>
            <input
              type="search"
              value={q}
              placeholder="Search teams and aliases"
              aria-label="Search teams"
              onChange={(e) => setQ(e.target.value)}
            />
          </form>

          <div ref={scroller} className="teams-list-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Team</th>
                  <th scope="col">Aliases</th>
                  <th scope="col" className="num">Entries</th>
                  <th scope="col" className="num">Last year</th>
                  <th scope="col">Lineage</th>
                </tr>
              </thead>
              <tbody>
                {teams.map((t) => (
                  <tr
                    key={t.id}
                    className={t.id === selected?.id ? 'active' : undefined}
                    aria-selected={t.id === selected?.id}
                  >
                    <td>
                      <button type="button" className="drv-link" onClick={() => select(t)}>
                        {t.name}
                      </button>
                    </td>
                    <td>{otherAliases(t).join(' · ') || <span className="muted">—</span>}</td>
                    <td className="num">{t.entryCount}</td>
                    <td className="num">{t.lastYear ?? <span className="muted">—</span>}</td>
                    <td>
                      {t.predecessorName ? (
                        <>from {t.predecessorName}</>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
                {teams.length === 0 && !loading && (
                  <tr>
                    <td colSpan={5} className="muted">
                      No teams match.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            <div ref={sentinel} className="teams-list-foot">
              {hasMore ? (
                <button type="button" className="btn" disabled={loading} onClick={loadMore}>
                  {loading ? 'Loading…' : 'Load more'}
                </button>
              ) : (
                teams.length > 0 && (
                  <span className="muted">
                    {teams.length} {teams.length === 1 ? 'team' : 'teams'}
                  </span>
                )
              )}
            </div>
          </div>
        </div>

        <aside className="teams-editor" aria-label="Team editor">
          {error && <p className="error">{error}</p>}
          {!selected ? (
            <p className="muted">Pick a team from the list to edit its aliases, merge a duplicate or set its lineage.</p>
          ) : (
            <>
              <h3>{selected.name}</h3>
              <p className="muted">{teamHint(selected)}</p>

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
              <form className="users-form team-pick-form" onSubmit={(e) => void doMerge(e)}>
                <TeamSearchPicker
                  inputId="team-merge-source"
                  label={`Team to merge into ${selected.name}`}
                  placeholder="Search every team and alias"
                  excludeId={selected.id}
                  disabled={busy}
                  value={mergeSource}
                  onChange={setMergeSource}
                />
                <button type="submit" className="btn" disabled={busy || !mergeSource}>
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
              <form className="users-form team-pick-form" onSubmit={(e) => void setPred(e)}>
                <TeamSearchPicker
                  inputId="team-predecessor"
                  label={`Predecessor for ${selected.name}`}
                  placeholder="Search every team and alias"
                  excludeId={selected.id}
                  disabled={busy}
                  value={predecessor}
                  onChange={setPredecessor}
                />
                <button type="submit" className="btn" disabled={busy || !predecessor}>
                  Set predecessor
                </button>
              </form>
            </>
          )}
        </aside>
      </div>
    </section>
  )
}
