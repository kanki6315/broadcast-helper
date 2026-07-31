import { useEffect, useRef, useState } from 'react'

interface ManufacturerRow {
  name: string
  entryCount: number
  logoVersion: number | null
  /** Dark theme: recolour the mark white instead of the white pill. Null until a logo exists. */
  invertOnDark: boolean | null
}

export default function LogosPage() {
  const [rows, setRows] = useState<ManufacturerRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const inputs = useRef<Record<string, HTMLInputElement | null>>({})

  async function load() {
    const res = await fetch('/api/manufacturers')
    if (res.ok) setRows(await res.json())
  }

  useEffect(() => {
    void load()
  }, [])

  async function upload(name: string, file: File) {
    setBusy(true)
    setError(null)
    const form = new FormData()
    form.append('file', file)
    const res = await fetch(`/api/manufacturer-logos?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      body: form,
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(`${name}: ${body?.message ?? `upload failed (${res.status})`}`)
    }
    await load()
    setBusy(false)
  }

  async function setInvert(name: string, value: boolean) {
    setError(null)
    const res = await fetch(`/api/manufacturer-logos/${encodeURIComponent(name.toLowerCase())}/invert`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ invertOnDark: value }),
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(`${name}: ${body?.message ?? `update failed (${res.status})`}`)
    }
    await load()
  }

  const missing = rows.filter((r) => r.logoVersion == null).length

  return (
    <section className="logos-page">
      <h2>Manufacturer logos</h2>
      <p>
        Upload a logo per manufacturer (SVG or PNG recommended). Logos are matched to entries by
        manufacturer name and reused on every sheet, replacing the car-model text. Until a logo is
        uploaded, the sheet shows the manufacturer name. Tick &ldquo;White on dark&rdquo; for
        single-colour wordmarks: the dark theme then recolours them white instead of painting a
        white pill behind them. Leave multi-colour badges unticked — inverting flattens them.
      </p>
      {error && <p className="error">{error}</p>}

      <h3>
        Manufacturers ({rows.length}){missing > 0 && ` — ${missing} without a logo`}
      </h3>
      <table>
        <thead>
          <tr>
            <th>Manufacturer</th>
            <th>Entries</th>
            <th>Logo</th>
            <th>White on dark</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.name}>
              <td>{r.name}</td>
              <td>{r.entryCount}</td>
              <td>
                {r.logoVersion != null ? (
                  <img
                    className={`logo-thumb${r.invertOnDark ? ' logo-thumb--invert' : ''}`}
                    src={`/api/manufacturer-logos/${encodeURIComponent(
                      r.name.toLowerCase(),
                    )}/data?v=${r.logoVersion}`}
                    alt={r.name}
                  />
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td>
                {r.logoVersion != null ? (
                  <input
                    type="checkbox"
                    checked={r.invertOnDark ?? false}
                    disabled={busy}
                    aria-label={`Recolour ${r.name} logo white on the dark theme`}
                    onChange={(e) => setInvert(r.name, e.target.checked)}
                  />
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td>
                <input
                  ref={(el) => {
                    inputs.current[r.name] = el
                  }}
                  type="file"
                  accept="image/*,.svg"
                  disabled={busy}
                  onChange={(e) => e.target.files?.[0] && upload(r.name, e.target.files[0])}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
