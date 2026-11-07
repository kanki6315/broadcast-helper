import { Fragment, useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getJson } from '../lib/api'
import { historyDrivers, type HistoryDriver, type HistorySeason, type SeriesHistory } from '../lib/multiSeasonRecap'
import { raceTagsByOrdinal } from '../lib/raceForm'
import RaceCell from '../components/RaceCell'
import { hasPointsNotes, posScopeOf, raceLegendTags, RecapLegend } from './season/ChampionshipGrid'
import './season.css'
import './multi-season-recap.css'

function SeasonFinish({ driver, season }: { driver: HistoryDriver; season: HistorySeason }) {
  const row = driver.years.get(season.id)?.recap
  return row ? <Link to={`/seasons/${season.id}/standings`} className="ms-finish">
    <strong>P{row.position}</strong><span>{row.totalPoints} pts</span>
  </Link> : <span className="muted" title="No imported standings for this driver">—</span>
}

const STATS = [
  { key: 'starts', label: 'Starts', short: 'St' },
  { key: 'wins', label: 'Wins', short: 'W' },
  { key: 'podiums', label: 'Podiums', short: 'P3' },
  { key: 'poles', label: 'Poles', short: 'Pole' },
] as const

function DriverTotals({ driver }: { driver: HistoryDriver }) {
  return <span className="ms-driver-stats">{STATS.map(stat => {
    const known = stat.key === 'poles' ? driver.qualifyingSessions > 0 : driver.statsAvailable
    const value = known ? driver[stat.key] : null
    const tier = value && stat.key === 'wins' ? 'res-win' : value && stat.key === 'podiums' ? 'res-top3' : ''
    return <span key={stat.key} className={`ms-stat ${tier} ${value === 0 ? 'ms-stat-zero' : ''}`}
      data-stat={stat.key} aria-label={`${stat.label}: ${value ?? 'unavailable'}`}
      title={`${stat.label}: ${value ?? 'no imported data'} · selected seasons`}>{value ?? '—'}</span>
  })}</span>
}

function ResultsGrid({ seasons, drivers }: { seasons: HistorySeason[]; drivers: HistoryDriver[] }) {
  return <div className="grid-frame ms-frame"><table className="grid-table ms-results">
    <caption className="sr-only">Driver results grouped by season; start and finish positions for each round</caption>
    <thead><tr><th rowSpan={2} className="ident ms-driver">Driver
      <span className="ms-driver-stats ms-stat-head">{STATS.map(stat => <abbr key={stat.key} title={stat.label}>{stat.short}</abbr>)}</span>
    </th>
      {seasons.map(s => <th key={s.id} colSpan={(s.recap?.rounds.length ?? 0) + 1} className="ms-year">
        <Link to={`/seasons/${s.id}`}>{s.year}</Link>
        <span className="ms-coverage">{s.recap?.rounds.filter(r => r.raceCount > 0).length ?? 0} rounds</span>
      </th>)}
    </tr><tr>{seasons.map(s => <Fragment key={s.id}>
      {s.recap?.rounds.map((round, i) => <th key={round.round} className={`round-head ${i === 0 ? 'ms-boundary' : ''}`}>
        {round.eventId ? <Link to={`/seasons/${s.id}/results?event=${round.eventId}`}><span className="venue">{round.venue}</span><span className="rd">Rd {round.round}</span></Link>
          : <><span className="venue">{round.venue}</span><span className="rd">Rd {round.round} · not imported</span></>}
      </th>)}<th className="ms-standing">Pos<br /><span className="muted">Pts</span></th>
    </Fragment>)}</tr></thead>
    <tbody>{drivers.map(driver => <tr key={driver.key}><th scope="row" className="ident ms-driver">{driver.name}<DriverTotals driver={driver} />
      {driver.driverId == null && <small className="ms-unresolved">Identity not linked</small>}</th>
      {seasons.map(s => <Fragment key={s.id}>
        {s.recap?.rounds.map((round, i) => {
          const year = driver.years.get(s.id)
          const races = year?.recap?.cells[round.round]
          return <td key={round.round} className={`race-cell ${i === 0 ? 'ms-boundary' : ''}`}>
            {round.eventId == null || round.raceCount === 0 || (year && !year.recap)
              ? <span className="muted" title="Recap results unavailable">—</span>
              : <RaceCell races={races} raceTags={raceTagsByOrdinal(round.races)} />}
          </td>
        })}<td className="ms-standing"><SeasonFinish driver={driver} season={s} /></td>
      </Fragment>)}
    </tr>)}</tbody>
  </table></div>
}

export default function MultiSeasonRecapPage() {
  const { seriesId } = useParams()
  const [params, setParams] = useSearchParams()
  const [data, setData] = useState<SeriesHistory | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let cancelled = false
    setData(null); setError(null)
    getJson<SeriesHistory>(`/api/series/${seriesId}/recap`).then(value => { if (!cancelled) setData(value) })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load recap') })
    return () => { cancelled = true }
  }, [seriesId, attempt])
  useEffect(() => {
    if (data) document.title = `Multi-season recap · ${data.seriesName} · Pit Pass`
    return () => { document.title = 'Pit Pass' }
  }, [data])
  const seasonParam = params.get('seasons')
  const seasons = useMemo(() => {
    if (!data) return []
    const selected = seasonParam?.split(',').map(Number)
    const matches = data.seasons.filter(s => !selected || selected.includes(s.id))
    return matches.length ? matches : data.seasons
  }, [data, seasonParam])
  const query = params.get('q') ?? ''
  const currentOnly = params.get('field') === 'current'
  const sort = params.get('sort') ?? 'standing'
  const latest = data?.seasons.at(-1)
  const currentKeys = useMemo(() => new Set(latest ? historyDrivers([latest]).map(d => d.key) : []), [latest])
  const drivers = useMemo(() => {
    const normalize = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    return historyDrivers(seasons).filter(d => normalize(d.name).includes(normalize(query.trim())) && (!currentOnly || currentKeys.has(d.key)))
      .sort((a, b) => {
        if (sort === 'name') return a.name.localeCompare(b.name)
        if (sort === 'starts' || sort === 'wins' || sort === 'podiums' || sort === 'poles') {
          if (!a.statsAvailable || !b.statsAvailable) return Number(!a.statsAvailable) - Number(!b.statsAvailable) || a.name.localeCompare(b.name)
          return b[sort] - a[sort] || a.name.localeCompare(b.name)
        }
        const newest = seasons.at(-1)?.id ?? 0
        return (a.years.get(newest)?.recap?.position ?? Infinity) - (b.years.get(newest)?.recap?.position ?? Infinity) || a.name.localeCompare(b.name)
      })
  }, [seasons, query, currentOnly, currentKeys, sort])
  function update(key: string, value: string | null) {
    const next = new URLSearchParams(params)
    next.delete('view'); next.delete('driver')
    if (value) next.set(key, value); else next.delete(key)
    setParams(next, { replace: true })
  }
  if (error) return <div className="error-panel" role="alert">Couldn’t load the multi-season recap. {error} <button onClick={() => setAttempt(n => n + 1)}>Retry</button></div>
  if (!data) return <div className="skeleton-block" aria-label="Loading multi-season recap" aria-busy="true"><span className="skeleton" /><span className="skeleton" style={{ height: '12rem' }} /></div>
  const recaps = seasons.flatMap(s => s.recap ? [s.recap] : [])
  return <section className="ms-page">
    <header className="season-head">
      <div className="season-toolbar">
        <div className="season-title-row">
          <h1>{data.seriesName}</h1>
          <nav className="seg season-year-strip" aria-label="Series seasons">
            {data.seasons.slice().reverse().map(s =>
              <Link key={s.id} className="seg-btn" to={`/seasons/${s.id}`}>{s.year}</Link>)}
          </nav>
        </div>
      </div>
    </header>
    {data.seasons.length === 0 ? <p className="empty-state">No main seasons imported yet. <Link to="/manage/imports">Import results</Link> to start a recap.</p> : <>
      <div className="ms-controls">
        <div className="page-title-row ms-title"><h2>Multi-season recap</h2><span className="ms-count" role="status">{drivers.length} drivers</span></div>
        <div className="ms-filter"><div className="seg season-year-strip" role="group" aria-label="Included seasons">{data.seasons.map(s => {
          const selected = seasons.some(x => x.id === s.id)
          return <button key={s.id} className={`seg-btn ${selected ? 'active' : ''}`} aria-pressed={selected} disabled={selected && seasons.length === 1}
            onClick={() => update('seasons', (selected ? seasons.filter(x => x.id !== s.id) : [...seasons, s]).map(x => x.id).join(','))}>{s.year}</button>
        })}</div></div>
        <div className="ms-filter"><label className="sr-only" htmlFor="ms-driver-search">Driver</label><input id="ms-driver-search" type="search" aria-label="Find a driver" placeholder="Find a driver…" value={query} onChange={e => update('q', e.target.value)} /></div>
        <div className="ms-filter"><label className="ms-field"><input type="checkbox" checked={currentOnly} onChange={e => update('field', e.target.checked ? 'current' : null)} /> {latest?.year} drivers only</label></div>
        <div className="ms-filter"><label className="sr-only" htmlFor="ms-sort">Sort by</label><select id="ms-sort" aria-label="Sort drivers" value={sort} onChange={e => update('sort', e.target.value)}>
          <option value="standing">Latest selected standing</option><option value="name">Driver name</option><option value="starts">Most starts</option><option value="wins">Most wins</option><option value="podiums">Most podiums</option><option value="poles">Most poles</option>
        </select></div>
      </div>
      {seasons.some(s => !s.recap) && <p className="ms-note">Standings not imported for {seasons.filter(s => !s.recap).map(s => s.year).join(', ')}. Available statistics are included; race recap cells require standings.</p>}
      {drivers.length === 0 ? <p className="empty-state">No drivers match these filters. <button onClick={() => { const next = new URLSearchParams(params); next.delete('q'); next.delete('field'); setParams(next) }}>Clear driver filters</button></p>
        : <><details className="ms-guide">
            <summary>Reading the recap<span className="ms-guide-scope">Totals cover selected seasons · Imported standings may be in progress</span></summary>
            <div className="ms-guide-content">
              <RecapLegend tags={raceLegendTags(recaps)} scope={posScopeOf(recaps)} pointsNotes={hasPointsNotes(recaps)} />
              <p>St = starts · W = wins · P3 = podiums · Pole = qualifying poles · — = unavailable or no standing.</p>
            </div>
          </details>
          <ResultsGrid seasons={seasons} drivers={drivers} />
        </>}
    </>}
  </section>
}
