import { useEffect, useState, type FormEvent } from 'react'

interface Series {
  id: number
  name: string
  abbreviation: string | null
  aliases: string[]
}

export default function SeriesPage() {
  const [series, setSeries] = useState<Series[]>([])
  const [name, setName] = useState('')
  const [abbreviation, setAbbreviation] = useState('')
  const [aliasDrafts, setAliasDrafts] = useState<Record<number, string>>({})
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
        e.g. alias <em>IMSA Michelin Endurance Cup</em> on the IMSA series.
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
              <th>Name</th>
              <th>Abbreviation</th>
              <th>Aliases</th>
            </tr>
          </thead>
          <tbody>
            {series.map((s) => (
              <tr key={s.id}>
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
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
