import { useEffect, useState } from 'react'
import { getJson, type Recap } from '../../lib/api'
import { assignPosition, baselineIssue, project, SCORING_SOURCE, supportedChampionship, type Scenario } from '../../lib/championshipCalculator'
import { useSeason } from './SeasonLayout'
import './calculator.css'

export default function CalculatorPage() {
  const { hub, classFilter } = useSeason()
  return <CalculatorClasses key={hub.id} initialClass={classFilter} />
}
function CalculatorClasses({ initialClass }: { initialClass: string | null }) {
  const { hub, classes, classColor } = useSeason()
  const supported = hub.championships.filter(supportedChampionship)
  const families = [...new Set(supported.map(c => c.isCup))]
  const [cup, setCup] = useState(families[0] ?? false)
  const championships = supported.filter(c => c.isCup === cup)
    .sort((a, b) => classes.findIndex(c => c.name === a.className) - classes.findIndex(c => c.name === b.className))
    .filter((c, i, all) => all.findIndex(other => other.className === c.className) === i).slice(0, 4)
  const [enabled, setEnabled] = useState<string[]>([initialClass ?? championships[0]?.className ?? ''])
  const available = championships.map(c => c.className ?? '')
  const visible = enabled.filter(name => available.includes(name))
  const shown = visible.length ? visible : available.slice(0, 1)
  const [eventId, setEventId] = useState(0)
  const [drafts, setDrafts] = useState<Record<number, Scenario>>({})
  return <section className="calculator">
    <h2>Championship calculator</h2>
    <p className="calculator-note">Enable one to four classes. Each panel compares its own selected teams.</p>
    {!championships.length ? <p className="empty-state">Import IMSA WeatherTech team standings to calculate a scenario.</p> : <>
      <div className="calculator-context">
        <label className="calculator-field">Championship<select aria-label="Championship" value={String(cup)} onChange={e => { setCup(e.target.value === 'true'); setDrafts({}) }}>
          {families.map(isCup => <option key={String(isCup)} value={String(isCup)}>{isCup ? 'Michelin Endurance Cup' : 'WeatherTech Championship'}</option>)}
        </select></label>
        <label className="calculator-field">Event<select aria-label="Event" value={eventId || hub.events[0]?.id || 0} onChange={e => { setEventId(Number(e.target.value)); setDrafts({}) }}>
          {hub.events.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}
        </select></label>
      </div>
      <div className="class-chips" role="group" aria-label="Visible calculator classes">
        {championships.map(c => {
          const name = c.className ?? ''
          const active = shown.includes(name)
          return <button key={c.id} type="button" className={`class-chip${active ? ' active' : ''}`} aria-pressed={active}
            aria-label={`Show ${name}`} disabled={active && shown.length === 1}
            style={{ '--chip-color': classColor(name) } as React.CSSProperties}
            onClick={() => setEnabled(active ? shown.filter(n => n !== name) : [...shown, name])}><i className="swatch" />{name || c.title}</button>
        })}
      </div>
      <p className="calculator-note" role="status">{shown.length} {shown.length === 1 ? 'class' : 'classes'} shown. Hiding a class keeps its scenario while you stay on this page.</p>
      <div className={`calculator-panels${shown.length > 1 ? ' multiple' : ''}`}>
        {championships.filter(c => shown.includes(c.className ?? '')).map(c => <section key={c.id} className="calculator-panel" aria-label={`${c.className} calculator`}>
          <h3>{c.className ?? c.title}</h3>
          <ChampionshipScenario champId={c.id} cup={cup} eventId={eventId} suggestEvent={id => setEventId(old => old || id)}
            scenario={drafts[c.id] ?? {}} setScenario={update => setDrafts(old => ({ ...old, [c.id]: typeof update === 'function' ? update(old[c.id] ?? {}) : update }))} />
        </section>)}
      </div>
    </>}
  </section>
}
function ChampionshipScenario({ champId, cup, eventId, suggestEvent, scenario, setScenario }: {
  champId: number; cup: boolean; eventId: number; suggestEvent: (id: number) => void
  scenario: Scenario; setScenario: React.Dispatch<React.SetStateAction<Scenario>>
}) {
  const { hub } = useSeason()
  const [recap, setRecap] = useState<Recap | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let cancelled = false
    setError('')
    getJson<Recap>(`/api/championships/${champId}/calculator`).then(data => {
      if (!cancelled) setRecap(data)
    }).catch(e => { if (!cancelled) setError(String(e.message ?? e)) })
    return () => { cancelled = true }
  }, [champId, attempt])
  useEffect(() => {
    if (recap && !eventId) suggestEvent(hub.events.find(e => !baselineIssue(recap, e.id))?.id ?? hub.events[0]?.id ?? 0)
  }, [recap, eventId, hub.events, suggestEvent])
  if (error) return <div role="alert" className="error-panel">{error} <button onClick={() => setAttempt(n => n + 1)}>Retry</button></div>
  if (!hub.events.length) return <p className="empty-state">Add an event to this season to start a scenario.</p>
  if (!recap || !eventId) return <p role="status">Loading imported standings…</p>
  const issue = baselineIssue(recap, eventId)
  const last = [...recap.rounds].reverse().find(r => recap.rows.some(row => Object.hasOwn(row.pointsByRound, r.round)))
  return <>
    <p className="calculator-note">Baseline: latest imported totals{last ? ` · through ${last.venue}` : ''}. {cup ? 'Checkpoints for an unscored event are simulated below.' : 'Qualifying for an unscored weekend is added below.'}</p>
    {issue ? <p className="error-panel" role="status">{issue}</p> : <ScenarioEditor key={eventId} recap={recap} cup={cup} eventId={eventId} scenario={scenario} setScenario={setScenario} />}
  </>
}
function ScenarioEditor({ recap, cup, eventId, scenario, setScenario }: {
  recap: Recap; cup: boolean; eventId: number; scenario: Scenario; setScenario: React.Dispatch<React.SetStateAction<Scenario>>
}) {
  const phases = cup ? (recap.rounds.find(r => r.eventId === eventId)?.sessions.map(s => s.name) ?? []) : ['Qualifying', 'Race']
  const rows = project(recap, scenario, cup, phases.length)
  const remaining = recap.rows.filter(r => !scenario[r.competitorKey])
  const name = (r: Recap['rows'][number]) => `${r.carNumber ? '#' + r.carNumber + ' · ' : ''}${r.teamName ?? r.competitorName ?? r.competitorKey}`
  return <>
    <div className="calculator-actions">
      <label className="calculator-field">Add team<select aria-label="Add team" value="" onChange={e => { const key = e.target.value; if (key) setScenario(s => ({ ...s, [key]: { positions: phases.map(() => 0), adjustment: 0 } })) }}>
        <option value="">{remaining.length ? 'Select a team…' : 'All teams selected'}</option>
        {remaining.map(r => <option key={r.competitorKey} value={r.competitorKey}>{name(r)}</option>)}
      </select></label>
      <button type="button" disabled={!rows.length} onClick={() => setScenario({})}>Reset scenario</button>
    </div>
    <p className="calculator-note">Positions and gaps below are among selected teams only. Blank positions add zero points. Choosing an occupied position swaps the two teams. Scroll the table for position controls.</p>
    {!rows.length ? <p className="empty-state">Select the teams you want to compare, then assign positions in class.</p> : <div className="calculator-scroll"><table className="calculator-table">
      <caption className="sr-only">Projected points among selected teams</caption>
      <thead><tr><th scope="col">Rank*</th><th scope="col">Team</th><th scope="col">Projected</th><th scope="col">Gap*</th><th scope="col">Imported</th>{phases.map(p => <th scope="col" key={p}>{p}</th>)}<th scope="col">Adjustment</th><th scope="col">Added</th><th scope="col"><span className="sr-only">Remove</span></th></tr></thead>
      <tbody>{rows.map(row => <tr key={row.competitorKey}>
        <td>{row.tied ? '=' : ''}{row.rank}</td><th scope="row">{name(row)}</th><td className="calculator-total">{row.total}</td><td>{row.gap}</td><td>{row.totalPoints}</td>
        {phases.map((phase, i) => <td key={phase}><select aria-label={`${phase} position for ${name(row)}`} value={scenario[row.competitorKey].positions[i] ?? 0}
          onChange={e => setScenario(s => assignPosition(s, row.competitorKey, i, Number(e.target.value)))}>
          <option value={0}>—</option>{Array.from({ length: Math.max(40, recap.rows.length) }, (_, p) => <option key={p + 1} value={p + 1}>P{p + 1}</option>)}
        </select><small>+{row.awards[i]}</small></td>)}
        <td><input type="number" step="any" aria-label={`Points adjustment for ${name(row)}`} value={scenario[row.competitorKey].adjustment} onChange={e => { const adjustment = Number(e.target.value); if (Number.isFinite(adjustment)) setScenario(s => ({ ...s, [row.competitorKey]: { ...s[row.competitorKey], adjustment } })) }} /></td>
        <td>{row.added > 0 ? '+' : ''}{row.added}</td>
        <td><button aria-label={`Remove ${name(row)}`} onClick={() => setScenario(s => Object.fromEntries(Object.entries(s).filter(([key]) => key !== row.competitorKey)))}>Remove</button></td>
      </tr>)}</tbody>
    </table></div>}
    <p className="calculator-note" role="status">{rows.length} teams selected. *Comparison rank and gap, not full championship position. Equal totals remain tied.</p>
    <details className="calculator-breakdown"><summary>Points calculation and assumptions</summary>
      <p>Projected = imported total + {cup ? 'checkpoint points' : 'qualifying points + race points'} + adjustment. Enter a negative adjustment for a points penalty. Guest eligibility and official tie-breaks are not applied.</p>
      <p>{cup ? 'Each checkpoint: P1 = 5, P2 = 4, P3 = 3, P4 onward = 2. Every checkpoint here is simulated.' : 'Qualifying: 35, 32, 30, 28, 26, then 25 down to 1; P30 onward = 1. Race points are ten times qualifying points. Enter qualifying positions manually, including after qualifying has happened.'}</p>
      {rows.map(r => <p key={r.competitorKey}>{name(r)}: {r.totalPoints} + {r.awards.join(' + ')} + ({scenario[r.competitorKey].adjustment}) = <strong>{r.total}</strong></p>)}
      <a href={SCORING_SOURCE} target="_blank" rel="noreferrer">2026 IMSA scoring regulations</a>
    </details>
    <p className="calculator-note">Temporary scenario on this screen. Actual standings and recap tables are unchanged.</p>
  </>
}
