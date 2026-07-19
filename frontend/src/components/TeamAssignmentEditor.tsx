import { useEffect, useMemo, useState } from 'react'

interface SeasonSummary {
  id: number
  year: number
  roundCount: number
}

interface AssignmentResponse {
  id: number
  driverId: number
  teamName: string
  privateer: boolean
  effectiveFromRound: number
}

interface DriverResponse {
  id: number
  name: string
  assignments: AssignmentResponse[]
}

interface TeamAssignmentsResponse {
  seasonId: number
  year: number
  roundCount: number
  teamNames: string[]
  drivers: DriverResponse[]
}

interface DraftAssignment {
  key: string
  teamName: string
  privateer: boolean
  effectiveFromRound: number
}

interface DraftDriver {
  id: number
  name: string
  assignments: DraftAssignment[]
}

let draftKey = 0

function assignmentKey(id?: number) {
  return id != null ? `saved-${id}` : `draft-${++draftKey}`
}

function toDraft(data: TeamAssignmentsResponse): DraftDriver[] {
  return data.drivers.map((driver) => ({
    id: driver.id,
    name: driver.name,
    assignments: (driver.assignments.length > 0
      ? driver.assignments.map((assignment) => ({
          key: assignmentKey(assignment.id),
          teamName: assignment.privateer ? 'Privateer' : assignment.teamName,
          privateer: assignment.privateer,
          effectiveFromRound: assignment.effectiveFromRound,
        }))
      : [{ key: assignmentKey(), teamName: 'Privateer', privateer: true, effectiveFromRound: 1 }]
    ).sort((a, b) => a.effectiveFromRound - b.effectiveFromRound),
  }))
}

function snapshot(drivers: DraftDriver[]) {
  return JSON.stringify(drivers.map((driver) => ({
    id: driver.id,
    assignments: driver.assignments.map(({ teamName, privateer, effectiveFromRound }) => ({
      teamName: teamName.trim(), privateer, effectiveFromRound,
    })),
  })))
}

export default function TeamAssignmentEditor({
  seasons,
  onError,
  onDirtyChange,
}: {
  seasons: SeasonSummary[]
  onError: (message: string | null) => void
  onDirtyChange: (dirty: boolean) => void
}) {
  const [seasonId, setSeasonId] = useState<number | null>(seasons[0]?.id ?? null)
  const [data, setData] = useState<TeamAssignmentsResponse | null>(null)
  const [drivers, setDrivers] = useState<DraftDriver[]>([])
  const [baseline, setBaseline] = useState('')
  const [needsSetup, setNeedsSetup] = useState(false)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  const dirty = baseline !== '' && snapshot(drivers) !== baseline
  const canSave = dirty || needsSetup

  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange(false), [onDirtyChange])

  useEffect(() => {
    if (seasonId == null) return
    const controller = new AbortController()
    setLoading(true)
    setSaved(false)
    void fetch(`/api/seasons/${seasonId}/team-assignments`, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          const body = await response.json().catch(() => null)
          throw new Error(body?.message ?? `Backend returned ${response.status}`)
        }
        return response.json() as Promise<TeamAssignmentsResponse>
      })
      .then((next) => {
        const draft = toDraft(next)
        setData(next)
        setDrivers(draft)
        setBaseline(snapshot(draft))
        setNeedsSetup(next.drivers.some((driver) => driver.assignments.length === 0))
        setExpanded(new Set(next.drivers.filter((driver) => driver.assignments.length > 1).map((driver) => driver.id)))
        onError(null)
      })
      .catch((error: unknown) => {
        if ((error as { name?: string }).name !== 'AbortError') {
          onError(error instanceof Error ? error.message : 'Failed to load team assignments')
        }
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [seasonId, onError])

  const teamNames = useMemo(() => {
    const names = new Map<string, string>()
    data?.teamNames.forEach((name) => names.set(name.toLocaleLowerCase(), name))
    drivers.forEach((driver) => driver.assignments.forEach((assignment) => {
      const name = assignment.teamName.trim()
      if (name && !assignment.privateer) names.set(name.toLocaleLowerCase(), name)
    }))
    return [...names.values()].sort((a, b) => a.localeCompare(b))
  }, [data, drivers])

  const summary = useMemo(() => {
    const teams = new Set<string>()
    let privateers = 0
    for (const driver of drivers) {
      const latest = driver.assignments.at(-1)
      if (!latest) continue
      if (latest.privateer) privateers += 1
      else teams.add(latest.teamName.trim().toLocaleLowerCase())
    }
    return { teams: teams.size, privateers }
  }, [drivers])

  function updateAssignment(driverId: number, key: string, changes: Partial<DraftAssignment>) {
    setSaved(false)
    setDrivers((current) => current.map((driver) => driver.id !== driverId ? driver : {
      ...driver,
      assignments: driver.assignments
        .map((assignment) => assignment.key === key ? { ...assignment, ...changes } : assignment)
        .sort((a, b) => a.effectiveFromRound - b.effectiveFromRound),
    }))
  }

  function updateTeam(driverId: number, key: string, value: string) {
    updateAssignment(driverId, key, {
      teamName: value,
      privateer: value.trim().toLocaleLowerCase() === 'privateer',
    })
  }

  function addChange(driver: DraftDriver) {
    const last = driver.assignments.at(-1)
    if (!last || !data || last.effectiveFromRound >= data.roundCount) return
    const next: DraftAssignment = {
      key: assignmentKey(),
      teamName: last.teamName,
      privateer: last.privateer,
      effectiveFromRound: last.effectiveFromRound + 1,
    }
    setDrivers((current) => current.map((item) => item.id === driver.id
      ? { ...item, assignments: [...item.assignments, next] }
      : item))
    setExpanded((current) => new Set(current).add(driver.id))
    setSaved(false)
  }

  function removeChange(driverId: number, key: string) {
    setDrivers((current) => current.map((driver) => driver.id === driverId
      ? { ...driver, assignments: driver.assignments.filter((assignment) => assignment.key !== key) }
      : driver))
    setSaved(false)
  }

  function discard() {
    if (!data) return
    const draft = toDraft(data)
    setDrivers(draft)
    setBaseline(snapshot(draft))
    setSaved(false)
    onError(null)
  }

  function validationMessage() {
    for (const driver of drivers) {
      if (driver.assignments.some((assignment) => !assignment.teamName.trim())) {
        return `Choose a team or Privateer for ${driver.name}.`
      }
      if (driver.assignments[0]?.effectiveFromRound !== 1) {
        return `${driver.name} needs an assignment beginning in Round 01.`
      }
      const rounds = driver.assignments.map((assignment) => assignment.effectiveFromRound)
      if (new Set(rounds).size !== rounds.length) {
        return `${driver.name} has two assignments beginning in the same round.`
      }
    }
    return null
  }

  async function save() {
    if (seasonId == null) return
    const validation = validationMessage()
    if (validation) { onError(validation); return }
    setSaving(true)
    setSaved(false)
    try {
      const response = await fetch(`/api/seasons/${seasonId}/team-assignments`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assignments: drivers.flatMap((driver) => driver.assignments.map((assignment) => ({
          driverId: driver.id,
          teamName: assignment.privateer ? 'Privateer' : assignment.teamName.trim(),
          privateer: assignment.privateer,
          effectiveFromRound: assignment.effectiveFromRound,
        }))) }),
      })
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        throw new Error(body?.message ?? `Backend returned ${response.status}`)
      }
      const next = await response.json() as TeamAssignmentsResponse
      const draft = toDraft(next)
      setData(next)
      setDrivers(draft)
      setBaseline(snapshot(draft))
      setNeedsSetup(false)
      setSaved(true)
      onError(null)
    } catch (error) {
      onError(error instanceof Error ? error.message : 'Team assignments could not be saved')
    } finally {
      setSaving(false)
    }
  }

  if (seasons.length === 0) {
    return <div className="empty-state team-assignment-empty"><h3>No championship years</h3><p>Import a driver-based season before assigning teams.</p></div>
  }

  return (
    <section className="team-assignment-editor" aria-labelledby="team-assignment-title">
      <div className="team-assignment-head">
        <div>
          <h3 id="team-assignment-title">Team assignments</h3>
          <p>Assign teams to drivers for this championship year. Changes update imported rounds and apply to future imports.</p>
        </div>
        <label className="team-season-picker">
          <span>Season</span>
          <select value={seasonId ?? ''} onChange={(event) => {
            if (dirty && !window.confirm('Discard unsaved team assignments?')) return
            setSeasonId(Number(event.target.value))
          }}>
            {seasons.map((season) => <option key={season.id} value={season.id}>{season.year}</option>)}
          </select>
        </label>
      </div>

      {loading ? (
        <div className="team-roster-skeleton" aria-label="Loading team assignments" aria-busy="true">
          {[0, 1, 2, 3, 4].map((row) => <span className="skeleton" key={row} />)}
        </div>
      ) : drivers.length === 0 ? (
        <div className="empty-state team-assignment-empty"><h3>No drivers found</h3><p>Import results or an entry list for this season, then return to assign teams.</p></div>
      ) : (
        <>
          <datalist id="team-name-options">
            <option value="Privateer">Independent entry</option>
            {teamNames.map((name) => <option key={name} value={name} />)}
          </datalist>
          <div className="team-roster-frame">
            <table className="team-roster-table">
              <thead><tr><th className="team-expand-col"><span className="sr-only">History</span></th><th>Driver</th><th>Team assignment</th><th>Effective from</th><th className="team-actions-col">Actions</th></tr></thead>
              {drivers.map((driver) => {
                const open = expanded.has(driver.id)
                const latest = driver.assignments.at(-1)!
                return (
                  <tbody key={driver.id} className={open ? 'is-expanded' : undefined}>
                    <tr>
                      <td className="team-expand-col"><button type="button" className="team-expand" aria-expanded={open} aria-label={`${open ? 'Collapse' : 'Expand'} assignment history for ${driver.name}`} onClick={() => setExpanded((current) => { const next = new Set(current); if (next.has(driver.id)) next.delete(driver.id); else next.add(driver.id); return next })}>⌄</button></td>
                      <th scope="row">{driver.name}</th>
                      <td><input list="team-name-options" value={latest.teamName} aria-label={`Team for ${driver.name}`} onChange={(event) => updateTeam(driver.id, latest.key, event.target.value)} /></td>
                      <td className="num">Round {String(latest.effectiveFromRound).padStart(2, '0')}</td>
                      <td className="team-actions-col"><button type="button" className="team-add-compact" onClick={() => addChange(driver)} disabled={!data || latest.effectiveFromRound >= data.roundCount}>Add team change</button></td>
                    </tr>
                    {open && (
                      <tr className="team-history-row"><td></td><td colSpan={4}>
                        <div className="team-history" aria-label={`Assignment history for ${driver.name}`}>
                          <div className="team-history-head"><span>Team</span><span>Effective from</span><span>Effective to</span><span>Status</span><span></span></div>
                          {driver.assignments.map((assignment, index) => {
                            const next = driver.assignments[index + 1]
                            return <div className="team-history-line" key={assignment.key}>
                              <div className="team-history-field team-history-team"><span className="team-history-field-label">Team</span><input list="team-name-options" value={assignment.teamName} aria-label={`Team assignment ${index + 1} for ${driver.name}`} onChange={(event) => updateTeam(driver.id, assignment.key, event.target.value)} /></div>
                              <div className="team-history-field team-history-from"><span className="team-history-field-label">Effective from</span><select value={assignment.effectiveFromRound} aria-label={`Starting round for assignment ${index + 1} for ${driver.name}`} disabled={index === 0} onChange={(event) => updateAssignment(driver.id, assignment.key, { effectiveFromRound: Number(event.target.value) })}>
                                {Array.from({ length: Math.max(data?.roundCount ?? 1, 1) }, (_, round) => round + 1).map((round) => <option key={round} value={round}>Round {String(round).padStart(2, '0')}</option>)}
                              </select></div>
                              <div className="team-history-field team-history-to"><span className="team-history-field-label">Effective to</span><span className="num">{next ? `Round ${String(next.effectiveFromRound - 1).padStart(2, '0')}` : 'Onward'}</span></div>
                              <div className="team-history-field team-history-status"><span className="team-history-field-label">Status</span><span>{next ? 'Previous' : 'Current'}</span></div>
                              <button type="button" className="team-remove" disabled={index === 0} onClick={() => removeChange(driver.id, assignment.key)} aria-label={`Remove assignment ${index + 1} for ${driver.name}`}>×</button>
                            </div>
                          })}
                          <button type="button" className="team-add-change" onClick={() => addChange(driver)} disabled={!data || latest.effectiveFromRound >= data.roundCount}>+ Add team change</button>
                        </div>
                      </td></tr>
                    )}
                  </tbody>
                )
              })}
            </table>
            <div className="team-roster-status" aria-live="polite">
              <span>{drivers.length} driver{drivers.length === 1 ? '' : 's'} · {summary.teams} team{summary.teams === 1 ? '' : 's'} · {summary.privateers} privateer{summary.privateers === 1 ? '' : 's'}</span>
              {saved && <span className="team-saved"><span aria-hidden="true">✓</span> Assignments saved</span>}
            </div>
          </div>
          <div className="team-assignment-actions">
            <button type="button" onClick={discard} disabled={!dirty || saving}>Discard changes</button>
            <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!canSave || saving}>{saving ? 'Saving assignments…' : 'Save assignments'}</button>
          </div>
        </>
      )}
    </section>
  )
}
