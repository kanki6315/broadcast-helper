import { useCallback, useEffect, useRef, useState } from 'react'
import 'flag-icons/css/flag-icons.min.css'
import './driver-modal.css'
import {
  getJson,
  type DriverChampMatrix,
  type DriverProfile,
  type DriverStats,
  type NamedFormatLine,
  type QualiLine,
} from '../lib/api'
import { flagCode } from '../lib/countries'
import { formatPoints } from '../pages/season/ChampionshipGrid'
import { useInfoModal } from './infoModal'
import NotesSection from './NotesSection'
import RaceLine from './RaceLine'
import { raceTagsByOrdinal } from '../lib/raceForm'

/* ------------------------------------------------------------------------- */
/* Formatting helpers                                                          */
/* ------------------------------------------------------------------------- */

function parseIsoDate(iso: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) return null
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
}

function formatDob(iso: string): string {
  const d = parseIsoDate(iso)
  if (!d) return iso
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(d)
}

function ageFrom(iso: string): number | null {
  const d = parseIsoDate(iso)
  if (!d) return null
  const now = new Date()
  let age = now.getFullYear() - d.getFullYear()
  const beforeBirthday =
    now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())
  if (beforeBirthday) age -= 1
  return age >= 0 && age < 130 ? age : null
}

/* ------------------------------------------------------------------------- */
/* Career stats                                                                */
/* ------------------------------------------------------------------------- */

/** "Sprint 3W 4P3 · Main 2W 2P3 · 2 poles" — a format's line only names the
 * numbers it has; a season of zeros still shows its starts so the line reads
 * as participation, not absence. */
function formatSplit(byFormat: NamedFormatLine[], quali: QualiLine): string {
  const parts = byFormat
    .filter((l) => l.starts > 0)
    .map((l) => {
      const bits = [`${l.formatName}`]
      bits.push(`${l.wins}W`)
      if (l.podiums > 0) bits.push(`${l.podiums}P3`)
      return bits.join(' ')
    })
  if (quali.poles > 0) parts.push(`${quali.poles} pole${quali.poles === 1 ? '' : 's'}`)
  return parts.join(' · ')
}

function StatsSection({ stats }: { stats: DriverStats }) {
  if (stats.career.starts === 0) return null
  const chips: { label: string; value: number }[] = [
    { label: 'Starts', value: stats.career.starts },
    { label: 'Wins', value: stats.career.wins },
    { label: 'Podiums', value: stats.career.podiums },
    { label: 'Top 5s', value: stats.career.top5s },
    { label: 'Poles', value: stats.career.poles },
    { label: 'DNFs', value: stats.career.dnfs },
  ]
  // A per-series all-time line only earns its place once the series spans more
  // than one season here; otherwise it restates the single season line below.
  const multiSeason = new Set(
    stats.bySeries
      .filter((s) => stats.seasons.filter((x) => x.seriesName === s.seriesName).length > 1)
      .map((s) => s.seriesId),
  )
  return (
    <section className="dm-stats" aria-label="Career stats">
      <dl className="dm-stat-chips">
        {chips.map((c) => (
          <div key={c.label} className="dm-stat-chip">
            <dd className="num">{c.value}</dd>
            <dt>{c.label}</dt>
          </div>
        ))}
      </dl>
      <ul className="dm-stat-lines">
        {stats.bySeries
          .filter((s) => multiSeason.has(s.seriesId))
          .map((s) => (
            <li key={`sr-${s.seriesId}`}>
              <span className="dm-stat-when">{s.seriesName} all-time</span>
              <span className="dm-stat-what">{formatSplit(s.byFormat, s.quali)}</span>
            </li>
          ))}
        {stats.seasons.map((s) => (
          <li key={`se-${s.seasonId}-${s.className}`}>
            <span className="dm-stat-when">
              {s.year} {s.seriesName}
            </span>
            <span className="dm-stat-what">{formatSplit(s.byFormat, s.quali)}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/* ------------------------------------------------------------------------- */
/* Championship result matrix                                                  */
/* ------------------------------------------------------------------------- */

function ChampSection({ champ }: { champ: DriverChampMatrix }) {
  const hasPoints = Object.keys(champ.pointsByRound).length > 0
  const label = `${champ.title} — start and finish by round`
  return (
    <section className="dm-champ">
      <div className="dm-champ-head">
        <h3 title={champ.title}>{champ.title}</h3>
        <span className="dm-champ-pos">
          <b>P{champ.position}</b> · {formatPoints(champ.totalPoints)} pts
        </span>
      </div>
      {champ.rounds.length === 0 ? (
        <p className="dm-quiet">No calendar published for this championship yet.</p>
      ) : (
        <div className="dm-matrix-scroll" tabIndex={0} role="region" aria-label={label}>
          <table className="dm-matrix">
            <caption className="sr-only">{label}</caption>
            <thead>
              <tr>
                <th className="dm-mx-rowhead" scope="col">
                  <span className="sr-only">Row</span>
                </th>
                {champ.rounds.map((r) => (
                  <th key={r.round} scope="col">
                    <span className="venue">{r.venue}</span>
                    <span className="rd">Rd {r.round}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                <th className="dm-mx-rowhead" scope="row">
                  Result
                </th>
                {champ.rounds.map((r) => {
                  const races = champ.cells[r.round]
                  const raceTags = raceTagsByOrdinal(r.races)
                  return (
                    <td key={r.round} className="dm-mx-cell">
                      {races && races.length > 0 ? (
                        races.map((race) => (
                          <RaceLine key={race.race} r={race} tag={raceTags.get(race.race)} />
                        ))
                      ) : (
                        <span className="cell-skip" title="Did not enter this round">
                          ·
                        </span>
                      )}
                    </td>
                  )
                })}
              </tr>
              {hasPoints && (
                <tr>
                  <th className="dm-mx-rowhead" scope="row">
                    Pts
                  </th>
                  {champ.rounds.map((r) => {
                    const pts = champ.pointsByRound[r.round]
                    return (
                      <td key={r.round} className="dm-mx-pts">
                        {pts != null ? formatPoints(pts) : <span className="muted">—</span>}
                      </td>
                    )
                  })}
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/* ------------------------------------------------------------------------- */
/* Bio edit form                                                               */
/* ------------------------------------------------------------------------- */

interface BioDraft {
  dateOfBirth: string
  hometown: string
  placeOfBirth: string
  pronunciation: string
}

function BioForm({
  profile,
  onDone,
  onCancel,
}: {
  profile: DriverProfile
  onDone: () => void
  onCancel: () => void
}) {
  const [draft, setDraft] = useState<BioDraft>({
    dateOfBirth: profile.dateOfBirth ?? '',
    hometown: profile.hometown ?? '',
    placeOfBirth: profile.placeOfBirth ?? '',
    pronunciation: profile.pronunciation ?? '',
  })
  const [photoFile, setPhotoFile] = useState<File | null>(null)
  const [removePhoto, setRemovePhoto] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function set<K extends keyof BioDraft>(key: K, value: string) {
    setDraft((d) => ({ ...d, [key]: value }))
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const r = await fetch(`/api/drivers/${profile.id}/bio`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          dateOfBirth: draft.dateOfBirth || null,
          hometown: draft.hometown,
          placeOfBirth: draft.placeOfBirth,
          pronunciation: draft.pronunciation,
        }),
      })
      if (!r.ok) throw new Error(`Backend returned ${r.status}`)
      if (photoFile) {
        const form = new FormData()
        form.append('file', photoFile)
        const p = await fetch(`/api/drivers/${profile.id}/photo`, { method: 'POST', body: form })
        if (!p.ok) throw new Error(`Photo upload failed (${p.status})`)
      } else if (removePhoto && profile.photoVersion != null) {
        await fetch(`/api/drivers/${profile.id}/photo`, { method: 'DELETE' })
      }
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed')
      setSaving(false)
    }
  }

  const hasPhoto = profile.photoVersion != null && !removePhoto

  return (
    <div className="dm-form">
      <label>
        <span>Date of birth</span>
        <input
          type="date"
          value={draft.dateOfBirth}
          onChange={(e) => set('dateOfBirth', e.target.value)}
        />
      </label>
      <label>
        <span>Hometown</span>
        <input
          type="text"
          value={draft.hometown}
          placeholder="e.g. Portland, OR"
          onChange={(e) => set('hometown', e.target.value)}
        />
      </label>
      <label>
        <span>Place of birth</span>
        <input
          type="text"
          value={draft.placeOfBirth}
          placeholder="e.g. Turin, Italy"
          onChange={(e) => set('placeOfBirth', e.target.value)}
        />
      </label>
      <label>
        <span>Pronunciation</span>
        <input
          type="text"
          value={draft.pronunciation}
          placeholder="e.g. YOO-le KAT-soo-burg"
          onChange={(e) => set('pronunciation', e.target.value)}
        />
      </label>
      <div className="dm-form-photo">
        <span>Photo</span>
        <div className="dm-form-photo-row">
          <label className="btn dm-file">
            {photoFile ? photoFile.name : hasPhoto ? 'Replace photo…' : 'Choose photo…'}
            <input
              type="file"
              accept="image/*"
              onChange={(e) => {
                setPhotoFile(e.target.files?.[0] ?? null)
                setRemovePhoto(false)
              }}
            />
          </label>
          {photoFile && (
            <button type="button" className="btn" onClick={() => setPhotoFile(null)}>
              Clear selection
            </button>
          )}
          {!photoFile && hasPhoto && (
            <button type="button" className="btn" onClick={() => setRemovePhoto(true)}>
              Remove photo
            </button>
          )}
          {removePhoto && <span className="dm-form-hint">Photo will be removed on save.</span>}
        </div>
      </div>
      {error && <p className="error-panel dm-form-error">{error}</p>}
      <div className="dm-form-actions">
        <button type="button" className="btn btn-primary" disabled={saving} onClick={() => void save()}>
          {saving ? 'Saving…' : 'Save bio'}
        </button>
        <button type="button" className="btn" disabled={saving} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------------- */
/* The modal                                                                   */
/* ------------------------------------------------------------------------- */

export default function DriverModal({
  driverId,
  onClose,
}: {
  driverId: number
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const { openTeam } = useInfoModal()
  const [profile, setProfile] = useState<DriverProfile | null>(null)
  const [stats, setStats] = useState<DriverStats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [reload, setReload] = useState(0)
  const flushNotes = useRef<() => void>(() => {})

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  useEffect(() => {
    let cancelled = false
    setError(null)
    getJson<DriverProfile>(`/api/drivers/${driverId}/profile`)
      .then((p) => !cancelled && setProfile(p))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load driver'))
    // Stats are additive: the modal renders fine without them, so a failure
    // here stays silent rather than blocking the profile.
    getJson<DriverStats>(`/api/drivers/${driverId}/stats`)
      .then((s) => !cancelled && setStats(s))
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [driverId, reload])

  const saveNotes = useCallback(
    async (text: string) => {
      const r = await fetch(`/api/drivers/${driverId}/notes`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ notes: text }),
        keepalive: true,
      })
      if (!r.ok) throw new Error(String(r.status))
    },
    [driverId],
  )

  const close = useCallback(() => {
    flushNotes.current()
    onClose()
  }, [onClose])

  // Drill-through to the team modal replaces this one — a dirty notes draft
  // must not die with it.
  function goToTeam(name: string) {
    flushNotes.current()
    openTeam(name)
  }

  const flag = flagCode(profile?.country)
  const age = profile?.dateOfBirth ? ageFrom(profile.dateOfBirth) : null
  const facts: { label: string; value: string; mono?: boolean }[] = []
  if (profile?.dateOfBirth) {
    facts.push({ label: 'Born', value: formatDob(profile.dateOfBirth), mono: true })
    if (age != null) facts.push({ label: 'Age', value: String(age), mono: true })
  }
  if (profile?.hometown) facts.push({ label: 'Hometown', value: profile.hometown })
  if (profile?.placeOfBirth) facts.push({ label: 'Birthplace', value: profile.placeOfBirth })

  return (
    <dialog
      className="dm no-print"
      ref={dialogRef}
      aria-label={profile ? `Driver: ${profile.name}` : 'Driver'}
      onCancel={(e) => {
        e.preventDefault()
        close()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) close()
      }}
    >
      <div className="dm-body">
        {error && (
          <div className="dm-pad">
            <p className="error-panel">{error}</p>
            <button type="button" className="btn" onClick={close}>
              Close
            </button>
          </div>
        )}

        {!error && !profile && (
          <div className="dm-pad" aria-label="Loading driver">
            <div className="dm-skel-head">
              <span className="skeleton dm-skel-photo" />
              <span className="skeleton dm-skel-name" />
            </div>
            <span className="skeleton" />
            <span className="skeleton" />
            <span className="skeleton dm-skel-wide" />
          </div>
        )}

        {profile && (
          <>
            <header className="dm-head">
              {profile.photoVersion != null && (
                <img
                  className="dm-photo"
                  src={`/api/drivers/${profile.id}/photo?v=${profile.photoVersion}`}
                  alt=""
                />
              )}
              <div className="dm-id">
                <h2 className="dm-name">
                  {profile.name}
                  {flag && <span className={`fi fi-${flag}`} title={profile.country ?? ''} />}
                  {profile.rating && (
                    <span className="dm-rating" title="Driver rating">
                      {profile.rating}
                    </span>
                  )}
                </h2>
                {profile.pronunciation && <p className="dm-pron">“{profile.pronunciation}”</p>}
                {profile.carNumber && (
                  <p className="dm-seat">
                    <span className="dm-car">#{profile.carNumber}</span>{' '}
                    {profile.teamName && profile.teamName.toLocaleLowerCase() !== 'privateer' && (
                      <button
                        type="button"
                        className="drv-link"
                        title={`Open ${profile.teamName}`}
                        onClick={() => goToTeam(profile.teamName!)}
                      >
                        {profile.teamName}
                      </button>
                    )}
                    {profile.teamName?.toLocaleLowerCase() === 'privateer' && (
                      <span className="dm-privateer">Privateer</span>
                    )}
                    {profile.className && <> · {profile.className}</>}
                    {profile.seriesName && (
                      <>
                        {' '}
                        · {profile.seriesName} {profile.year}
                      </>
                    )}
                  </p>
                )}
              </div>
              <button type="button" className="dm-close" aria-label="Close" onClick={close}>
                ✕
              </button>
            </header>

            <section className="dm-bio" aria-label="Bio">
              {editing ? (
                <BioForm
                  profile={profile}
                  onDone={() => {
                    setEditing(false)
                    setProfile(null)
                    setReload((n) => n + 1)
                  }}
                  onCancel={() => setEditing(false)}
                />
              ) : (
                <div className="dm-bio-row">
                  {facts.length > 0 ? (
                    <dl className="dm-facts">
                      {facts.map((f) => (
                        <div key={f.label} className="dm-fact">
                          <dt>{f.label}</dt>
                          <dd className={f.mono ? 'num' : undefined}>{f.value}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : (
                    <p className="dm-quiet">No bio yet.</p>
                  )}
                  <button type="button" className="btn dm-edit" onClick={() => setEditing(true)}>
                    {facts.length > 0 || profile.photoVersion != null ? 'Edit bio' : 'Add bio'}
                  </button>
                </div>
              )}
            </section>

            <div className="dm-scroll">
              {stats && <StatsSection stats={stats} />}

              {profile.championships.length === 0 ? (
                <p className="dm-quiet dm-pad-x">
                  No championship standings for this driver yet — they appear once a drivers
                  standings file is imported.
                </p>
              ) : (
                profile.championships.map((c) => <ChampSection key={c.championshipId} champ={c} />)
              )}

              <NotesSection initial={profile.notes ?? ''} save={saveNotes} flushRef={flushNotes} />
            </div>
          </>
        )}
      </div>
    </dialog>
  )
}
