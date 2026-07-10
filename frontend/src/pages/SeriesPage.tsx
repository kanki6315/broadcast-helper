import { useEffect, useState, type FormEvent } from 'react'

interface Series {
  id: number
  name: string
  abbreviation: string | null
  aliases: string[]
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

export default function SeriesPage() {
  const [series, setSeries] = useState<Series[]>([])
  const [name, setName] = useState('')
  const [abbreviation, setAbbreviation] = useState('')
  const [aliasDrafts, setAliasDrafts] = useState<Record<number, string>>({})
  const [expanded, setExpanded] = useState<number | null>(null)
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
        e.g. alias <em>IMSA Michelin Endurance Cup</em> on the IMSA series. Class colours set the
        header colour and order each class appears in on the sheet.
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
              <th>Class colours</th>
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
                <td>
                  {expanded === s.id ? (
                    <ClassStyleEditor seriesId={s.id} onError={setError} />
                  ) : (
                    <button type="button" onClick={() => setExpanded(s.id)}>
                      Edit…
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
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
