import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import TeamAssignmentEditor from '../components/TeamAssignmentEditor'
import { invalidateAllRecaps, invalidateRecap } from './season/ChampionshipGrid'

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

interface SeriesChampionship {
  id: number
  seasonId: number
  year: number
  title: string
  className: string | null
  kind: string | null
  isCup: boolean
  isOverall: boolean
  rowCount: number
}

interface SeriesGroup {
  id: number
  seasonId: number
  year: number
  family: string
  kind: string | null
  label: string
  isCup: boolean
  championshipCount: number
}

interface SeasonSummary {
  id: number
  year: number
  seriesId: number
  seriesName: string
  roundCount: number
  championshipCount: number
}

type SeriesPanel = 'identity' | 'aliases' | 'classes' | 'formats' | 'teams' | 'years'

export default function SeriesPage() {
  const [series, setSeries] = useState<Series[]>([])
  const [seasons, setSeasons] = useState<SeasonSummary[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const [seriesRes, seasonsRes] = await Promise.all([fetch('/api/series'), fetch('/api/seasons')])
      if (!seriesRes.ok) throw new Error(`Backend returned ${seriesRes.status}`)
      if (!seasonsRes.ok) throw new Error(`Backend returned ${seasonsRes.status}`)
      setSeries(await seriesRes.json())
      setSeasons(await seasonsRes.json())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to reach backend')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const selected = series.find((item) => item.id === selectedId) ?? null

  return (
    <section className="series-manager">
      <div className="series-manager-header">
        <div>
          <h2>Series settings</h2>
          <p>Select a series to manage its identity, classes, formats and imported years.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
          Add series
        </button>
      </div>

      {error && <p className="error-panel">{error}</p>}

      {loading ? (
        <div className="series-directory" aria-label="Loading series">
          {[0, 1, 2].map((key) => <div key={key} className="series-row-skeleton skeleton" />)}
        </div>
      ) : series.length === 0 ? (
        <div className="empty-state">
          <h2>No series yet</h2>
          <p>Add the first championship series to begin importing event data.</p>
          <button type="button" onClick={() => setCreating(true)}>Add series</button>
        </div>
      ) : (
        <div className="series-directory">
          {series.map((item) => {
            const years = seasons.filter((season) => season.seriesId === item.id)
            return (
              <button
                type="button"
                className="series-directory-row"
                key={item.id}
                onClick={() => setSelectedId(item.id)}
                aria-label={`Manage ${item.name}`}
              >
                <span className="series-directory-mark" aria-hidden="true">
                  {item.logoVersion != null ? (
                    <img src={`/api/series/${item.id}/logo/data?v=${item.logoVersion}`} alt="" />
                  ) : (
                    <span>{seriesInitials(item)}</span>
                  )}
                </span>
                <span className="series-directory-identity">
                  <strong>{item.name}</strong>
                  {item.abbreviation && <span>{item.abbreviation}</span>}
                </span>
                <span className="series-directory-years">
                  {years.length > 0 ? years.map((season) => (
                    <span className="series-year" key={season.id}>{season.year}</span>
                  )) : <span className="muted">No championship years</span>}
                </span>
                <span className="series-directory-arrow" aria-hidden="true">›</span>
              </button>
            )
          })}
        </div>
      )}

      {selected && (
        <SeriesManagementDialog
          series={selected}
          seasons={seasons.filter((season) => season.seriesId === selected.id)}
          onClose={() => setSelectedId(null)}
          onRefresh={load}
        />
      )}
      {creating && <AddSeriesDialog onClose={() => setCreating(false)} onCreated={load} />}
    </section>
  )
}

function seriesInitials(series: Series) {
  if (series.abbreviation?.trim()) return series.abbreviation.trim().slice(0, 4).toUpperCase()
  return series.name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0]).join('').toUpperCase()
}

function SeriesManagementDialog({
  series,
  seasons,
  onClose,
  onRefresh,
}: {
  series: Series
  seasons: SeasonSummary[]
  onClose: () => void
  onRefresh: () => Promise<void>
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [panel, setPanel] = useState<SeriesPanel>('identity')
  const [error, setError] = useState<string | null>(null)
  const [teamDirty, setTeamDirty] = useState(false)

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])

  const panels: Array<{ id: SeriesPanel; label: string }> = [
    { id: 'identity', label: 'Identity' },
    { id: 'aliases', label: 'Aliases' },
    { id: 'classes', label: 'Classes' },
    { id: 'formats', label: 'Race formats' },
    { id: 'teams', label: 'Teams' },
    { id: 'years', label: 'Years' },
  ]

  function canLeaveTeams() {
    return panel !== 'teams' || !teamDirty || window.confirm('Discard unsaved team assignments?')
  }

  function close() {
    if (canLeaveTeams()) onClose()
  }

  return (
    <dialog
      ref={dialogRef}
      className="series-management-dialog"
      aria-labelledby="series-dialog-title"
      onCancel={(event) => { event.preventDefault(); close() }}
      onClick={(event) => { if (event.target === dialogRef.current) close() }}
    >
      <div className="series-dialog-shell">
        <header className="series-dialog-header">
          <div className="series-dialog-title">
            <span className="series-dialog-mark" aria-hidden="true">
              {series.logoVersion != null ? (
                <img src={`/api/series/${series.id}/logo/data?v=${series.logoVersion}`} alt="" />
              ) : <span>{seriesInitials(series)}</span>}
            </span>
            <div>
              <h2 id="series-dialog-title">{series.name}</h2>
              <p>Series settings</p>
            </div>
          </div>
          <button type="button" className="series-dialog-close" onClick={close} aria-label="Close series settings">×</button>
        </header>

        <div className="series-dialog-layout">
          <nav className="series-dialog-nav" aria-label="Series settings sections">
            {panels.map((item) => (
              <button
                type="button"
                key={item.id}
                className={panel === item.id ? 'active' : ''}
                aria-current={panel === item.id ? 'page' : undefined}
                onClick={() => { if (!canLeaveTeams()) return; setPanel(item.id); setError(null) }}
              >{item.label}</button>
            ))}
          </nav>
          <div className="series-dialog-content">
            {error && <p className="error-panel">{error}</p>}
            {panel === 'identity' && (
              <SeriesIdentityEditor series={series} onError={setError} onRefresh={onRefresh} />
            )}
            {panel === 'aliases' && (
              <SeriesAliasEditor series={series} onError={setError} onRefresh={onRefresh} />
            )}
            {panel === 'classes' && (
              <div className="series-settings-stack">
                <SettingsSection title="Class colours" description="Set the display colour and order used across sheets and season pages.">
                  <ClassStyleEditor seriesId={series.id} onError={setError} />
                </SettingsSection>
                <SettingsSection title="Class names" description="Map source spellings and rename a class across every imported season.">
                  <ClassAliasEditor seriesId={series.id} onError={setError} />
                </SettingsSection>
                <SettingsSection title="Overall championships" description="Mark a championship that scores the whole field rather than one class. Its recap then shows every class's drivers, with start and finish positions read overall instead of in class.">
                  <OverallChampionshipEditor seriesId={series.id} onError={setError} />
                </SettingsSection>
                <SettingsSection title="Championship groups" description="A cup is a side award over a subset of the rounds, published under its own name. Uncheck a group the importer mistook for a cup so it sorts as a full-season championship and can feed the sheet's points column.">
                  <CupGroupEditor seriesId={series.id} onError={setError} />
                </SettingsSection>
                <SettingsSection title="Linked car numbers" description="When one entrant raced under a second number — a one-off renumbering or an entry handed to a new team mid-season — link that number to the entrant's standings number for the season and class. The recap then gathers every weekend onto the one row; event pages keep the number as raced.">
                  <CarNumberAliasEditor seriesId={series.id} seasons={seasons} onError={setError} />
                </SettingsSection>
              </div>
            )}
            {panel === 'formats' && (
              <SettingsSection title="Race formats" description="Control the stat buckets used for sprint, heat and feature sessions.">
                <RaceFormatEditor seriesId={series.id} onError={setError} />
              </SettingsSection>
            )}
            {panel === 'teams' && (
              <TeamAssignmentEditor seasons={seasons} onError={setError} onDirtyChange={setTeamDirty} />
            )}
            {panel === 'years' && (
              <SeasonDataEditor seasons={seasons} onError={setError} onRefresh={onRefresh} />
            )}
          </div>
        </div>
      </div>
    </dialog>
  )
}

function SettingsSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <section className="series-settings-section"><h3>{title}</h3><p>{description}</p>{children}</section>
}

function SeriesIdentityEditor({ series, onError, onRefresh }: {
  series: Series
  onError: (message: string | null) => void
  onRefresh: () => Promise<void>
}) {
  const [name, setName] = useState(series.name)
  const [abbreviation, setAbbreviation] = useState(series.abbreviation ?? '')
  const [saving, setSaving] = useState(false)

  async function save(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const response = await fetch(`/api/series/${series.id}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim(), abbreviation: abbreviation.trim() || null }),
      })
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        onError(body?.message ?? (response.status === 409 ? 'A series with that name already exists' : `Backend returned ${response.status}`))
        return
      }
      onError(null)
      await onRefresh()
    } finally { setSaving(false) }
  }

  async function uploadLogo(file: File) {
    const form = new FormData(); form.append('file', file)
    const response = await fetch(`/api/series/${series.id}/logo`, { method: 'POST', body: form })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      onError(body?.message ?? `Logo upload failed (${response.status})`); return
    }
    onError(null); await onRefresh()
  }

  async function removeLogo() {
    const response = await fetch(`/api/series/${series.id}/logo`, { method: 'DELETE' })
    if (!response.ok && response.status !== 404) { onError(`Backend returned ${response.status}`); return }
    onError(null); await onRefresh()
  }

  return (
    <SettingsSection title="Identity" description="Used throughout navigation, imports and broadcast sheets.">
      <form className="series-identity-form" onSubmit={save}>
        <label><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} required /></label>
        <label><span>Abbreviation</span><input value={abbreviation} onChange={(e) => setAbbreviation(e.target.value)} placeholder="e.g. IMSA" /></label>
        <div className="series-logo-field">
          <span>Series image</span>
          <div className="series-logo-preview">
            {series.logoVersion != null ? <img src={`/api/series/${series.id}/logo/data?v=${series.logoVersion}`} alt={`${series.name} logo`} /> : <span className="muted">No image uploaded</span>}
          </div>
          <div className="series-logo-actions">
            <label className="series-logo-upload">{series.logoVersion != null ? 'Replace image' : 'Upload image'}<input type="file" accept="image/*,.svg" onChange={(e) => e.target.files?.[0] && void uploadLogo(e.target.files[0])} /></label>
            {series.logoVersion != null && <button type="button" onClick={() => void removeLogo()}>Remove</button>}
          </div>
        </div>
        <div className="series-form-actions"><button className="btn btn-primary" type="submit" disabled={saving || !name.trim()}>{saving ? 'Saving…' : 'Save changes'}</button></div>
      </form>
    </SettingsSection>
  )
}

function SeriesAliasEditor({ series, onError, onRefresh }: {
  series: Series
  onError: (message: string | null) => void
  onRefresh: () => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  async function addAlias(event: FormEvent) {
    event.preventDefault(); const alias = draft.trim(); if (!alias) return
    const response = await fetch(`/api/series/${series.id}/aliases`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ alias }) })
    if (!response.ok) { onError(response.status === 409 ? 'That alias already exists' : `Backend returned ${response.status}`); return }
    setDraft(''); onError(null); await onRefresh()
  }
  return (
    <SettingsSection title="Series aliases" description="Match championship titles that publish under a different series name.">
      {series.aliases.length > 0 ? <ul className="series-alias-list">{series.aliases.map((alias) => <li key={alias}>{alias}</li>)}</ul> : <p className="muted">No aliases yet.</p>}
      <form className="series-alias-form" onSubmit={addAlias}><input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="e.g. IMSA Michelin Endurance Cup" aria-label="New series alias" /><button type="submit" disabled={!draft.trim()}>Add alias</button></form>
    </SettingsSection>
  )
}

function SeasonDataEditor({ seasons, onError, onRefresh }: {
  seasons: SeasonSummary[]
  onError: (message: string | null) => void
  onRefresh: () => Promise<void>
}) {
  const [confirming, setConfirming] = useState<SeasonSummary | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [note, setNote] = useState<string | null>(null)

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
      await onRefresh()
    } finally {
      setDeleting(false)
      setConfirming(null)
    }
  }

  return (
    <SettingsSection title="Championship years" description="Review imported seasons or clear a year before reimporting it from scratch.">
      <p>
        Deleting a year removes its rounds, results, grids, flags, entry lists and standings. Car
        images and series settings are kept.
      </p>
      {note && <p className="season-data-note">{note}</p>}
      {seasons.length === 0 ? (
        <p className="muted">No imported data yet.</p>
      ) : (
        <table className="series-years-table">
          <thead>
            <tr>
              <th>Year</th>
              <th>Rounds</th>
              <th>Championships</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {seasons.map((s) => (
              <tr key={s.id}>
                <td>{s.year}</td>
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
    </SettingsSection>
  )
}

function AddSeriesDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => Promise<void> }) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [name, setName] = useState('')
  const [abbreviation, setAbbreviation] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { const dialog = dialogRef.current; if (dialog && !dialog.open) dialog.showModal() }, [])
  async function submit(event: FormEvent) {
    event.preventDefault()
    const response = await fetch('/api/series', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.trim(), abbreviation: abbreviation.trim() || null }) })
    if (!response.ok) { setError(response.status === 409 ? 'A series with that name already exists' : `Backend returned ${response.status}`); return }
    await onCreated(); onClose()
  }
  return (
    <dialog ref={dialogRef} className="confirm-dialog add-series-dialog" aria-labelledby="add-series-title" onCancel={(event) => { event.preventDefault(); onClose() }} onClick={(event) => { if (event.target === dialogRef.current) onClose() }}>
      <h2 id="add-series-title">Add series</h2>
      <p>Create the series first, then open it to add its image, aliases and class settings.</p>
      {error && <p className="error-panel">{error}</p>}
      <form className="add-series-form" onSubmit={submit}>
        <label><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} autoFocus required /></label>
        <label><span>Abbreviation</span><input value={abbreviation} onChange={(e) => setAbbreviation(e.target.value)} placeholder="Optional" /></label>
        <div className="confirm-dialog-actions"><button type="button" onClick={onClose}>Cancel</button><button type="submit" className="btn btn-primary" disabled={!name.trim()}>Add series</button></div>
      </form>
    </dialog>
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

interface SeriesCarNumberAlias {
  id: number
  seasonId: number
  year: number
  className: string
  carNumber: string
  canonicalNumber: string
  note: string | null
}

interface SeriesCarNumberAliases {
  aliases: SeriesCarNumberAlias[]
  classesInUse: string[]
}

/**
 * The season+class "counts as" links (car_number_alias, V38) for the rare
 * entrant that raced under a second number. Listing is series-wide, grouped
 * by year; writes go through the season-scoped endpoints, so the add form
 * picks the year explicitly.
 */
function CarNumberAliasEditor({
  seriesId,
  seasons,
  onError,
}: {
  seriesId: number
  seasons: SeasonSummary[]
  onError: (message: string | null) => void
}) {
  const [data, setData] = useState<SeriesCarNumberAliases>({ aliases: [], classesInUse: [] })
  const [seasonId, setSeasonId] = useState('')
  const [className, setClassName] = useState('')
  const [carNumber, setCarNumber] = useState('')
  const [canonical, setCanonical] = useState('')
  const [note, setNote] = useState('')
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/car-number-aliases`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      setData((await res.json()) as SeriesCarNumberAliases)
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

  async function add() {
    if (!seasonId || !className.trim() || !carNumber.trim() || !canonical.trim()) return
    const res = await fetch(`/api/seasons/${seasonId}/car-number-aliases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        className: className.trim(),
        carNumber: carNumber.trim(),
        canonicalNumber: canonical.trim(),
        note: note.trim() || null,
      }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => null)
      onError(err?.message ?? `Backend returned ${res.status}`)
      return
    }
    onError(null)
    setCarNumber('')
    setCanonical('')
    setNote('')
    // Recaps cache per championship id for the session and the alias changes
    // how a whole season's worth of them match cells — this editor only knows
    // the season, so drop them all.
    invalidateAllRecaps()
    await load()
  }

  async function remove(a: SeriesCarNumberAlias) {
    const res = await fetch(`/api/seasons/${a.seasonId}/car-number-aliases/${a.id}`, { method: 'DELETE' })
    if (!res.ok && res.status !== 404) {
      onError(`Backend returned ${res.status}`)
      return
    }
    onError(null)
    invalidateAllRecaps()
    await load()
  }

  if (loading) return <span className="muted">Loading…</span>

  // Newest season first, matching the backend's ordering.
  const years = [...new Set(data.aliases.map((a) => a.year))]
  const seasonOptions = [...seasons].sort((a, b) => b.year - a.year)

  return (
    <div className="class-alias-editor">
      {data.aliases.length === 0 && <p className="muted">No linked numbers yet.</p>}
      {years.map((year) => (
        <div key={year} className="overall-champ-year">
          <h4>{year}</h4>
          {data.aliases
            .filter((a) => a.year === year)
            .map((a) => (
              <div key={a.id} className="class-alias-row">
                <span className="class-alias-name">#{a.carNumber}</span>
                <span className="muted">counts as</span>
                <span className="class-alias-name">#{a.canonicalNumber}</span>
                <span className="muted class-alias-scope">
                  {a.className}
                  {a.note ? ` — ${a.note}` : ''}
                </span>
                <button
                  type="button"
                  onClick={() => void remove(a)}
                  aria-label={`Unlink #${a.carNumber} from #${a.canonicalNumber} in ${a.year} ${a.className}`}
                >
                  ✕
                </button>
              </div>
            ))}
        </div>
      ))}

      <div className="class-alias-add">
        <select value={seasonId} onChange={(e) => setSeasonId(e.target.value)} aria-label="Season">
          <option value="">Season…</option>
          {seasonOptions.map((s) => (
            <option key={s.id} value={s.id}>
              {s.year}
            </option>
          ))}
        </select>
        <input
          value={className}
          onChange={(e) => setClassName(e.target.value)}
          placeholder="Class…"
          aria-label="Class"
          list={`car-number-alias-classes-${seriesId}`}
        />
        <datalist id={`car-number-alias-classes-${seriesId}`}>
          {data.classesInUse.map((c) => (
            <option key={c} value={c} />
          ))}
        </datalist>
        <input
          value={carNumber}
          onChange={(e) => setCarNumber(e.target.value)}
          placeholder="Raced as #…"
          aria-label="Car number as raced"
        />
        <input
          value={canonical}
          onChange={(e) => setCanonical(e.target.value)}
          placeholder="Counts as #…"
          aria-label="Standings car number"
        />
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Note (optional)…"
          aria-label="Note"
        />
        <button
          type="button"
          onClick={() => void add()}
          disabled={!seasonId || !className.trim() || !carNumber.trim() || !canonical.trim()}
        >
          Link
        </button>
      </div>
    </div>
  )
}

function OverallChampionshipEditor({
  seriesId,
  onError,
}: {
  seriesId: number
  onError: (message: string | null) => void
}) {
  const [championships, setChampionships] = useState<SeriesChampionship[]>([])
  const [saving, setSaving] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/championships`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      const data: { championships: SeriesChampionship[] } = await res.json()
      setChampionships(data.championships)
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

  async function setOverall(c: SeriesChampionship, isOverall: boolean) {
    setSaving(c.id)
    try {
      const res = await fetch(`/api/series/${seriesId}/championships/${c.id}/overall`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ isOverall }),
      })
      if (!res.ok) {
        const err = await res.json().catch(() => null)
        onError(err?.message ?? `Backend returned ${res.status}`)
        return
      }
      onError(null)
      // The recap is cached for the session and this changes how it renders,
      // so drop it or navigating back to the season serves the stale one.
      invalidateRecap(c.id)
      await load()
    } finally {
      setSaving(null)
    }
  }

  if (loading) return <span className="muted">Loading…</span>
  if (championships.length === 0) {
    return <p className="muted">No championships imported for this series yet.</p>
  }

  // Newest season first, matching the backend's ordering.
  const years = [...new Set(championships.map((c) => c.year))]

  return (
    <div className="overall-champ-editor">
      {years.map((year) => (
        <div key={year} className="overall-champ-year">
          <h4>{year}</h4>
          {championships
            .filter((c) => c.year === year)
            .map((c) => (
              <label key={c.id} className="overall-champ-row">
                <input
                  type="checkbox"
                  checked={c.isOverall}
                  disabled={saving === c.id}
                  // The row's own text runs the title into the meta line, so
                  // name the control explicitly like every other input here.
                  aria-label={`${c.title} scores the whole field`}
                  onChange={(e) => void setOverall(c, e.target.checked)}
                />
                <span className="overall-champ-title">{c.title}</span>
                <span className="muted overall-champ-meta">
                  {[c.className, c.kind, c.isCup ? 'cup' : null].filter(Boolean).join(' · ')}
                  {c.rowCount === 0 && ' · no standings'}
                </span>
              </label>
            ))}
        </div>
      ))}
    </div>
  )
}

function CupGroupEditor({
  seriesId,
  onError,
}: {
  seriesId: number
  onError: (message: string | null) => void
}) {
  const [groups, setGroups] = useState<SeriesGroup[]>([])
  const [saving, setSaving] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await fetch(`/api/series/${seriesId}/groups`)
      if (!res.ok) throw new Error(`Backend returned ${res.status}`)
      const data: { groups: SeriesGroup[] } = await res.json()
      setGroups(data.groups)
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

  async function setCup(g: SeriesGroup, isCup: boolean) {
    setSaving(g.id)
    try {
      const res = await fetch(`/api/series/${seriesId}/groups/${g.id}/cup`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ isCup }),
      })
      if (!res.ok) {
        const err = await res.json().catch(() => null)
        onError(err?.message ?? `Backend returned ${res.status}`)
        return
      }
      onError(null)
      await load()
    } finally {
      setSaving(null)
    }
  }

  if (loading) return <span className="muted">Loading…</span>
  if (groups.length === 0) {
    return <p className="muted">No championship groups for this series yet.</p>
  }

  // Newest season first, matching the backend's ordering.
  const years = [...new Set(groups.map((g) => g.year))]

  return (
    <div className="overall-champ-editor">
      {years.map((year) => (
        <div key={year} className="overall-champ-year">
          <h4>{year}</h4>
          {groups
            .filter((g) => g.year === year)
            .map((g) => (
              <label key={g.id} className="overall-champ-row">
                <input
                  type="checkbox"
                  checked={g.isCup}
                  disabled={saving === g.id}
                  aria-label={`${g.label} is a cup`}
                  onChange={(e) => void setCup(g, e.target.checked)}
                />
                <span className="overall-champ-title">{g.label}</span>
                <span className="muted overall-champ-meta">
                  {g.championshipCount === 1
                    ? '1 championship'
                    : `${g.championshipCount} championships`}
                </span>
              </label>
            ))}
        </div>
      ))}
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
