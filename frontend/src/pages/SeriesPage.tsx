import { useEffect, useRef, useState, type FormEvent } from 'react'

interface Series {
  id: number
  name: string
  abbreviation: string | null
  aliases: string[]
  logoVersion: number | null
}

interface ClassStyle {
  classCode: string
  ordinal: number
  color: string
}

interface ClassStylesResponse {
  styles: ClassStyle[]
  unconfiguredClasses: string[]
}

interface RaceFormat {
  id: number
  code: string
  name: string
  ordinal: number
  sessionCount: number
}

interface ClassAlias {
  id: number
  alias: string
  className: string
}

interface ClassAliasesResponse {
  aliases: ClassAlias[]
  classesInUse: string[]
}

interface SeasonSummary {
  id: number
  year: number
  seriesName: string
  roundCount: number
  championshipCount: number
}

export default function SeriesPage() {
  const [series, setSeries] = useState<Series[]>([])
  const [name, setName] = useState('')
  const [abbreviation, setAbbreviation] = useState('')
  const [aliasDrafts, setAliasDrafts] = useState<Record<number, string>>({})
  const [expanded, setExpanded] = useState<number | null>(null)
  const [formatsExpanded, setFormatsExpanded] = useState<number | null>(null)
  const [classNamesExpanded, setClassNamesExpanded] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function loadSeries() {
    try {
      const res = await fetch('/api/series')
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      setSeries(await res.json())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadSeries()
  }, [])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const res = await fetch('/api/series', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, abbreviation: abbreviation || null }),
    })
    if (res.status === 409) {
      setError('A series with that name already exists')
      return
    }
    if (!res.ok) {
      setError(`Backend returned ${res.status}`)
      return
    }
    setError(null)
    setName('')
    setAbbreviation('')
    await loadSeries()
  }

  async function uploadLogo(id: number, file: File) {
    const form = new FormData()
    form.append('file', file)
    const res = await fetch(`/api/series/${id}/logo`, { method: 'POST', body: form })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Logo upload failed (${res.status})`)
      return
    }
    setError(null)
    await loadSeries()
  }

  async function removeLogo(id: number) {
    const res = await fetch(`/api/series/${id}/logo`, { method: 'DELETE' })
    if (!res.ok && res.status !== 404) {
      setError(`Backend returned ${res.status}`)
      return
    }
    setError(null)
    await loadSeries()
  }

  async function addAlias(id: number) {
    const alias = (aliasDrafts[id] ?? '').trim()
    if (!alias) return
    const res = await fetch(`/api/series/${id}/aliases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ alias }),
    })
    if (res.status === 409) {
      setError('That alias already exists')
      return
    }
    if (!res.ok) {
      setError(`Backend returned ${res.status}`)
      return
    }
    setError(null)
    setAliasDrafts((d) => ({ ...d, [id]: '' }))
    await loadSeries()
  }

  return (
    <section>
      <form onSubmit={handleSubmit} className="series-form">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Series name (e.g. IMSA WeatherTech SportsCar Championship)"
          required
        />
        <input
          value={abbreviation}
          onChange={(e) => setAbbreviation(e.target.value)}
          placeholder="Abbreviation (e.g. IMSA)"
        />
        <button type="submit">Add series</button>
      </form>

      <p>
        Aliases map standings titles to a series when a championship publishes under its own name —
        e.g. alias <em>IMSA Michelin Endurance Cup</em> on the IMSA series. Class colours set the
        header colour and order each class appears in on the sheet. Class names rename a class
        everywhere and keep future imports mapped, for sources that spell one class differently —
        e.g. iRacing&apos;s <em>[L] Porsche 911</em> vs <em>Hosted All Cars</em>.
      </p>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Loading…</p>
      ) : series.length === 0 ? (
        <p>No series yet. Add the first one above.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Logo</th>
              <th>Name</th>
              <th>Abbreviation</th>
              <th>Aliases</th>
              <th>Class colours</th>
              <th>Class names</th>
              <th>Race formats</th>
            </tr>
          </thead>
          <tbody>
            {series.map((s) => (
              <tr key={s.id}>
                <td>
                  <div className="series-logo-cell">
                    {s.logoVersion != null ? (
                      <img
                        className="series-logo-thumb"
                        src={`/api/series/${s.id}/logo/data?v=${s.logoVersion}`}
                        alt={`${s.name} logo`}
                      />
                    ) : (
                      <span className="muted">—</span>
                    )}
                    <div className="series-logo-actions">
                      <label className="series-logo-upload">
                        {s.logoVersion != null ? 'Replace' : 'Upload'}
                        <input
                          type="file"
                          accept="image/*,.svg"
                          onChange={(e) =>
                            e.target.files?.[0] && void uploadLogo(s.id, e.target.files[0])
                          }
                        />
                      </label>
                      {s.logoVersion != null && (
                        <button type="button" onClick={() => void removeLogo(s.id)}>
                          Remove
                        </button>
                      )}
                    </div>
                  </div>
                </td>
                <td>{s.name}</td>
                <td>{s.abbreviation ?? '—'}</td>
                <td>
                  {s.aliases.length > 0 && (
                    <div>
                      {s.aliases.map((a) => (
                        <div key={a}>{a}</div>
                      ))}
                    </div>
                  )}
                  <div className="alias-form">
                    <input
                      value={aliasDrafts[s.id] ?? ''}
                      onChange={(e) => setAliasDrafts((d) => ({ ...d, [s.id]: e.target.value }))}
                      placeholder="Add alias…"
                    />
                    <button type="button" onClick={() => addAlias(s.id)}>
                      Add
                    </button>
                  </div>
                </td>
                <td>
                  {expanded === s.id ? (
                    <ClassStyleEditor seriesId={s.id} onError={setError} />
                  ) : (
                    <button type="button" onClick={() => setExpanded(s.id)}>
                      Edit…
                    </button>
                  )}
                </td>
                <td>
                  {classNamesExpanded === s.id ? (
                    <ClassAliasEditor seriesId={s.id} onError={setError} />
                  ) : (
                    <button type="button" onClick={() => setClassNamesExpanded(s.id)}>
                      Edit…
                    </button>
                  )}
                </td>
                <td>
                  {formatsExpanded === s.id ? (
                    <RaceFormatEditor seriesId={s.id} onError={setError} />
                  ) : (
                    <button type="button" onClick={() => setFormatsExpanded(s.id)}>
                      Edit…
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <SeasonDataSection onError={setError} />
    </section>
  )
}

/**
 * Deleting a year's imported data exists for one reason: a botched or stale
 * import that should be redone from scratch. The wipe removes what the
 * importers created (rounds and championships); the season row, car images and
 * series settings stay, and reimporting reattaches to the same season.
 */
function SeasonDataSection({ onError }: { onError: (message: string | null) => void }) {
  const [seasons, setSeasons] = useState<SeasonSummary[]>([])
  const [confirming, setConfirming] = useState<SeasonSummary | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch('/api/seasons')
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      setSeasons(await res.json())
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function deleteData(s: SeasonSummary) {
    setDeleting(true)
    try {
      const res = await fetch(`/api/seasons/${s.id}/data`, { method: 'DELETE' })
      if (!res.ok) {
        const err = await res.json().catch(() => null)
        onError(err?.message ?? `Backend returned ${res.status}`)
        return
      }
      const r = (await res.json()) as { roundsDeleted: number; championshipsDeleted: number }
      onError(null)
      setNote(
        `Deleted ${r.roundsDeleted} round${r.roundsDeleted === 1 ? '' : 's'} and ` +
          `${r.championshipsDeleted} championship${r.championshipsDeleted === 1 ? '' : 's'} ` +
          `for ${s.seriesName} ${s.year}. The year is ready to reimport.`,
      )
      await load()
    } finally {
      setDeleting(false)
      setConfirming(null)
    }
  }

  return (
    <div className="season-data">
      <h2>Championship years</h2>
      <p>
        Delete a year's imported data — rounds, results, grids, flags, entry lists and standings —
        to reimport it from scratch. Car images and series settings are kept.
      </p>
      {note && <p className="season-data-note">{note}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : seasons.length === 0 ? (
        <p className="muted">No imported data yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Year</th>
              <th>Series</th>
              <th>Rounds</th>
              <th>Championships</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {seasons.map((s) => (
              <tr key={s.id}>
                <td>{s.year}</td>
                <td>{s.seriesName}</td>
                <td>{s.roundCount}</td>
                <td>{s.championshipCount}</td>
                <td>
                  <button type="button" onClick={() => setConfirming(s)}>
                    Delete data…
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirming && (
        <ConfirmSeasonDelete
          season={confirming}
          busy={deleting}
          onConfirm={() => void deleteData(confirming)}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  )
}

function ConfirmSeasonDelete({
  season,
  busy,
  onConfirm,
  onCancel,
}: {
  season: SeasonSummary
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  return (
    <dialog
      className="confirm-dialog"
      ref={dialogRef}
      aria-label={`Delete imported data for ${season.seriesName} ${season.year}`}
      onCancel={(e) => {
        e.preventDefault()
        if (!busy) onCancel()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current && !busy) onCancel()
      }}
    >
      <h3>
        Delete imported data for {season.seriesName} {season.year}?
      </h3>
      <p>
        This removes {season.roundCount} round{season.roundCount === 1 ? '' : 's'} and{' '}
        {season.championshipCount} championship{season.championshipCount === 1 ? '' : 's'} — every
        result, grid, flag, entry list and standings table for the year. Car images and series
        settings are kept. This cannot be undone; the year has to be reimported.
      </p>
      <div className="confirm-dialog-actions">
        <button type="button" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <button type="button" className="btn btn-danger" onClick={onConfirm} disabled={busy}>
          {busy ? 'Deleting…' : 'Delete data'}
        </button>
      </div>
    </dialog>
  )
}

/**
 * Per-series race formats: the stat buckets (Sprint, Main, Heat…) sessions are
 * classified into. Rename feeds every stats surface live; merge is a cleanup
 * for buckets the heuristic split that the broadcaster counts as one;
 * auto-assign backfills events imported before formats existed.
 */
function RaceFormatEditor({
  seriesId,
  onError,
}: {
  seriesId: number
  onError: (message: string | null) => void
}) {
  const [formats, setFormats] = useState<RaceFormat[]>([])
  const [names, setNames] = useState<Record<number, string>>({})
  const [newName, setNewName] = useState('')
  const [assignNote, setAssignNote] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/race-formats`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      const data: RaceFormat[] = await res.json()
      setFormats(data)
      setNames(Object.fromEntries(data.map((f) => [f.id, f.name])))
      onError(null)
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seriesId])

  async function call(url: string, method: string, body?: unknown) {
    const res = await fetch(url, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
    if (!res.ok) {
      const err = await res.json().catch(() => null)
      onError(err?.message ?? `Backend returned ${res.status}`)
      return false
    }
    onError(null)
    return true
  }

  async function rename(f: RaceFormat) {
    const name = (names[f.id] ?? '').trim()
    if (!name || name === f.name) return
    if (await call(`/api/race-formats/${f.id}`, 'PATCH', { name })) await load()
  }

  async function merge(fromId: number, intoId: number) {
    if (await call(`/api/race-formats/${fromId}/merge`, 'POST', { intoId })) await load()
  }

  async function remove(id: number) {
    if (await call(`/api/race-formats/${id}`, 'DELETE')) await load()
  }

  async function create() {
    const name = newName.trim()
    if (!name) return
    if (await call(`/api/series/${seriesId}/race-formats`, 'POST', { name })) {
      setNewName('')
      await load()
    }
  }

  async function autoAssign() {
    setAssignNote(null)
    const res = await fetch(`/api/series/${seriesId}/race-formats/auto-assign`, { method: 'POST' })
    if (!res.ok) {
      onError(`Backend returned ${res.status}`)
      return
    }
    const r = (await res.json()) as { eventsProcessed: number; sessionsAssigned: number }
    setAssignNote(`${r.sessionsAssigned} sessions assigned across ${r.eventsProcessed} events.`)
    await load()
  }

  if (loading) return <span className="muted">Loading…</span>

  return (
    <div className="race-format-editor">
      {formats.length === 0 && (
        <p className="muted">No formats yet — auto-assign classifies the imported races.</p>
      )}
      {formats.map((f) => (
        <div key={f.id} className="race-format-row">
          <input
            value={names[f.id] ?? f.name}
            aria-label={`Rename ${f.name}`}
            onChange={(e) => setNames((n) => ({ ...n, [f.id]: e.target.value }))}
            onBlur={() => void rename(f)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
            }}
          />
          <span className="muted race-format-count">
            {f.sessionCount} session{f.sessionCount === 1 ? '' : 's'}
          </span>
          <select
            value=""
            aria-label={`Merge ${f.name} into…`}
            onChange={(e) => {
              if (e.target.value) void merge(f.id, Number(e.target.value))
            }}
          >
            <option value="">Merge into…</option>
            {formats
              .filter((x) => x.id !== f.id)
              .map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}
                </option>
              ))}
          </select>
          {f.sessionCount === 0 && (
            <button type="button" onClick={() => void remove(f.id)} aria-label={`Remove ${f.name}`}>
              ✕
            </button>
          )}
        </div>
      ))}

      <div className="race-format-add">
        <input
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          placeholder="New format name…"
        />
        <button type="button" onClick={() => void create()} disabled={!newName.trim()}>
          Add
        </button>
        <button type="button" onClick={() => void autoAssign()}>
          Auto-assign formats
        </button>
      </div>
      {assignNote && <p className="muted">{assignNote}</p>}
    </div>
  )
}

/**
 * Per-series class names: rename a class across every season (entries,
 * championships, sheet styles) and keep future imports mapped via the recorded
 * alias, plus direct alias management for spellings known ahead of an import.
 */
function ClassAliasEditor({
  seriesId,
  onError,
}: {
  seriesId: number
  onError: (message: string | null) => void
}) {
  const [aliases, setAliases] = useState<ClassAlias[]>([])
  const [classesInUse, setClassesInUse] = useState<string[]>([])
  const [renameFrom, setRenameFrom] = useState('')
  const [renameTo, setRenameTo] = useState('')
  const [newAlias, setNewAlias] = useState('')
  const [newTarget, setNewTarget] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/class-aliases`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      const data: ClassAliasesResponse = await res.json()
      setAliases(data.aliases)
      setClassesInUse(data.classesInUse)
      onError(null)
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seriesId])

  async function rename() {
    const from = renameFrom
    const to = renameTo.trim()
    if (!from || !to) return
    setNote(null)
    const res = await fetch(`/api/series/${seriesId}/classes/rename`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => null)
      onError(err?.message ?? `Backend returned ${res.status}`)
      return
    }
    const r = (await res.json()) as { entriesRenamed: number; championshipsRenamed: number }
    onError(null)
    setNote(
      `Renamed ${r.entriesRenamed} entr${r.entriesRenamed === 1 ? 'y' : 'ies'} and ` +
        `${r.championshipsRenamed} championship${r.championshipsRenamed === 1 ? '' : 's'} to “${to}”. ` +
        `Future imports of “${from}” map automatically.`,
    )
    setRenameFrom('')
    setRenameTo('')
    await load()
  }

  async function addAlias() {
    const alias = newAlias.trim()
    const className = newTarget.trim()
    if (!alias || !className) return
    const res = await fetch(`/api/series/${seriesId}/class-aliases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ alias, className }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => null)
      onError(err?.message ?? `Backend returned ${res.status}`)
      return
    }
    onError(null)
    setNewAlias('')
    setNewTarget('')
    await load()
  }

  async function removeAlias(a: ClassAlias) {
    const res = await fetch(`/api/series/${seriesId}/class-aliases/${a.id}`, { method: 'DELETE' })
    if (!res.ok && res.status !== 404) {
      onError(`Backend returned ${res.status}`)
      return
    }
    onError(null)
    await load()
  }

  if (loading) return <span className="muted">Loading…</span>

  return (
    <div className="class-alias-editor">
      {aliases.length === 0 && <p className="muted">No class aliases yet.</p>}
      {aliases.map((a) => (
        <div key={a.id} className="class-alias-row">
          <span className="class-alias-name">{a.alias}</span>
          <span className="muted">→</span>
          <span className="class-alias-name">{a.className}</span>
          <button type="button" onClick={() => void removeAlias(a)} aria-label={`Remove alias ${a.alias}`}>
            ✕
          </button>
        </div>
      ))}

      <div className="class-alias-add">
        <input
          value={newAlias}
          onChange={(e) => setNewAlias(e.target.value)}
          placeholder="Source spelling…"
          aria-label="Alias source spelling"
        />
        <input
          value={newTarget}
          onChange={(e) => setNewTarget(e.target.value)}
          placeholder="Maps to class…"
          aria-label="Alias target class"
          list={`class-alias-targets-${seriesId}`}
        />
        <datalist id={`class-alias-targets-${seriesId}`}>
          {classesInUse.map((c) => (
            <option key={c} value={c} />
          ))}
        </datalist>
        <button type="button" onClick={() => void addAlias()} disabled={!newAlias.trim() || !newTarget.trim()}>
          Add
        </button>
      </div>

      <div className="class-alias-rename">
        <select
          value={renameFrom}
          onChange={(e) => setRenameFrom(e.target.value)}
          aria-label="Class to rename"
        >
          <option value="">Rename class…</option>
          {classesInUse.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        <input
          value={renameTo}
          onChange={(e) => setRenameTo(e.target.value)}
          placeholder="New name…"
          aria-label="New class name"
          list={`class-alias-targets-${seriesId}`}
        />
        <button type="button" onClick={() => void rename()} disabled={!renameFrom || !renameTo.trim()}>
          Rename
        </button>
      </div>
      <p className="muted class-alias-hint">
        Renaming updates every season&apos;s entries and championships, and records the old spelling
        as an alias. Renaming onto an existing class merges them.
      </p>
      {note && <p className="muted">{note}</p>}
    </div>
  )
}

function ClassStyleEditor({
  seriesId,
  onError,
}: {
  seriesId: number
  onError: (message: string | null) => void
}) {
  const [styles, setStyles] = useState<ClassStyle[]>([])
  const [unconfigured, setUnconfigured] = useState<string[]>([])
  const [newCode, setNewCode] = useState('')
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/class-styles`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      const data: ClassStylesResponse = await res.json()
      setStyles(data.styles)
      setUnconfigured(data.unconfiguredClasses)
      onError(null)
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seriesId])

  async function save(classCode: string, ordinal: number, color: string) {
    const res = await fetch(`/api/series/${seriesId}/class-styles/${encodeURIComponent(classCode)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ordinal, color }),
    })
    if (!res.ok) {
      onError(`Backend returned ${res.status}`)
      return
    }
    onError(null)
    await load()
  }

  async function remove(classCode: string) {
    const res = await fetch(`/api/series/${seriesId}/class-styles/${encodeURIComponent(classCode)}`, {
      method: 'DELETE',
    })
    if (!res.ok) {
      onError(`Backend returned ${res.status}`)
      return
    }
    onError(null)
    await load()
  }

  function addClass() {
    const code = newCode.trim()
    if (!code) return
    setNewCode('')
    void save(code, styles.length, '#1a1a1a')
  }

  if (loading) return <span className="muted">Loading…</span>

  return (
    <div className="class-style-editor">
      {styles.length === 0 && <p className="muted">No class colours yet.</p>}
      {styles.map((st) => (
        <div key={st.classCode} className="class-style-row">
          <input
            type="color"
            value={st.color}
            onChange={(e) => void save(st.classCode, st.ordinal, e.target.value)}
            aria-label={`${st.classCode} colour`}
          />
          <span className="class-style-code">{st.classCode}</span>
          <input
            type="number"
            className="class-style-ordinal"
            value={st.ordinal}
            onChange={(e) => void save(st.classCode, Number(e.target.value), st.color)}
            aria-label={`${st.classCode} order`}
          />
          <button type="button" onClick={() => void remove(st.classCode)} aria-label={`Remove ${st.classCode}`}>
            ✕
          </button>
        </div>
      ))}

      <div className="class-style-add">
        {unconfigured.length > 0 ? (
          <>
            <select value={newCode} onChange={(e) => setNewCode(e.target.value)}>
              <option value="">Add class…</option>
              {unconfigured.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
            <button type="button" onClick={addClass} disabled={!newCode}>
              Add
            </button>
          </>
        ) : (
          <>
            <input
              value={newCode}
              onChange={(e) => setNewCode(e.target.value)}
              placeholder="New class code…"
            />
            <button type="button" onClick={addClass} disabled={!newCode.trim()}>
              Add
            </button>
          </>
        )}
      </div>
    </div>
  )
}
