import { useEffect, useRef, useState } from 'react'

interface ManufacturerRow {
  name: string
  entryCount: number
  logoVersion: number | null
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

  const missing = rows.filter((r) => r.logoVersion == null).length

  return (
    <section>
      <p>
        Upload a logo per manufacturer (SVG or PNG recommended). Logos are matched to entries by
        manufacturer name and reused on every sheet, replacing the car-model text. Until a logo is
        uploaded, the sheet shows the manufacturer name.
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
                    className="logo-thumb"
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
