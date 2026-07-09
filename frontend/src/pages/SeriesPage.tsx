import { useEffect, useState, type FormEvent } from 'react'

interface Series {
  id: number
  name: string
  abbreviation: string | null
}

export default function SeriesPage() {
  const [series, setSeries] = useState<Series[]>([])
  const [name, setName] = useState('')
  const [abbreviation, setAbbreviation] = useState('')
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
            </tr>
          </thead>
          <tbody>
            {series.map((s) => (
              <tr key={s.id}>
                <td>{s.name}</td>
                <td>{s.abbreviation ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
