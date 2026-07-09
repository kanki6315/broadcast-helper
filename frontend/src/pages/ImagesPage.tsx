import { useCallback, useEffect, useRef, useState } from 'react'

interface Season {
  id: number
  year: number
  seriesName: string
}

interface ImageSummary {
  id: number
  carNumber: string
  sourceFilename: string | null
  uploadedAt: string
}

interface MissingCar {
  carNumber: string
  className: string
  teamName: string
}

interface BulkResult {
  filename: string
  status: 'MATCHED' | 'REPLACED' | 'UNMATCHED' | 'AMBIGUOUS'
  carNumber: string | null
  candidates: string[]
}

export default function ImagesPage() {
  const [seasons, setSeasons] = useState<Season[]>([])
  const [seasonId, setSeasonId] = useState<number | null>(null)
  const [images, setImages] = useState<ImageSummary[]>([])
  const [missing, setMissing] = useState<MissingCar[]>([])
  const [results, setResults] = useState<BulkResult[]>([])
  const [assignments, setAssignments] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Original File objects by name, so unmatched files can be assigned manually
  // without re-selecting them.
  const pendingFiles = useRef<Map<string, File>>(new Map())
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    void fetch('/api/seasons')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then((data: Season[]) => {
        setSeasons(data)
        if (data.length > 0) setSeasonId(data[0].id)
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [])

  const loadOverview = useCallback(async (id: number) => {
    const res = await fetch(`/api/car-images?seasonId=${id}`)
    if (res.ok) {
      const data = await res.json()
      setImages(data.images)
      setMissing(data.missing)
    }
  }, [])

  useEffect(() => {
    if (seasonId != null) void loadOverview(seasonId)
  }, [seasonId, loadOverview])

  async function uploadFiles(files: FileList) {
    if (seasonId == null) return
    setBusy(true)
    setError(null)
    pendingFiles.current = new Map(Array.from(files).map((f) => [f.name, f]))
    const form = new FormData()
    for (const f of Array.from(files)) form.append('files', f)
    const res = await fetch(`/api/car-images/bulk?seasonId=${seasonId}`, {
      method: 'POST',
      body: form,
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Upload failed (${res.status})`)
    } else {
      setResults(await res.json())
    }
    if (fileInput.current) fileInput.current.value = ''
    await loadOverview(seasonId)
    setBusy(false)
  }

  async function assign(filename: string) {
    if (seasonId == null) return
    const number = (assignments[filename] ?? '').trim()
    const file = pendingFiles.current.get(filename)
    if (!number || !file) return
    setBusy(true)
    const form = new FormData()
    form.append('file', file)
    const res = await fetch(
      `/api/car-images?seasonId=${seasonId}&carNumber=${encodeURIComponent(number)}`,
      { method: 'POST', body: form },
    )
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Assign failed (${res.status})`)
    } else {
      setError(null)
      setResults((rs) =>
        rs.map((r) => (r.filename === filename ? { ...r, status: 'MATCHED', carNumber: number } : r)),
      )
      await loadOverview(seasonId)
    }
    setBusy(false)
  }

  const needsAttention = results.filter((r) => r.status === 'UNMATCHED' || r.status === 'AMBIGUOUS')

  return (
    <section>
      <p>
        Bulk-upload car photos named with the car number (e.g. <code>31.png</code>,{' '}
        <code>2026_023_triarsi.jpg</code>). Images are matched per season, so shared numbers across
        series never collide, and each image carries over between events until replaced.
      </p>

      <div className="series-form">
        <select
          value={seasonId ?? ''}
          onChange={(e) => setSeasonId(Number(e.target.value))}
          disabled={seasons.length === 0}
        >
          {seasons.map((s) => (
            <option key={s.id} value={s.id}>
              {s.seriesName} {s.year}
            </option>
          ))}
        </select>
        <input
          ref={fileInput}
          type="file"
          accept="image/*"
          multiple
          disabled={busy || seasonId == null}
          onChange={(e) => e.target.files && uploadFiles(e.target.files)}
        />
      </div>

      {error && <p className="error">{error}</p>}

      {needsAttention.length > 0 && (
        <>
          <h3>Needs attention</h3>
          <table>
            <thead>
              <tr>
                <th>File</th>
                <th>Problem</th>
                <th>Assign to car #</th>
              </tr>
            </thead>
            <tbody>
              {needsAttention.map((r) => (
                <tr key={r.filename}>
                  <td>{r.filename}</td>
                  <td>
                    {r.status === 'AMBIGUOUS'
                      ? `Matches several cars: ${r.candidates.join(', ')}`
                      : 'No known car number in filename'}
                  </td>
                  <td>
                    <div className="alias-form">
                      <input
                        value={assignments[r.filename] ?? ''}
                        onChange={(e) =>
                          setAssignments((a) => ({ ...a, [r.filename]: e.target.value }))
                        }
                        placeholder="e.g. 023"
                      />
                      <button disabled={busy} onClick={() => assign(r.filename)}>
                        Assign
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {results.length > 0 && (
        <p>
          Last upload: {results.filter((r) => r.status === 'MATCHED').length} matched,{' '}
          {results.filter((r) => r.status === 'REPLACED').length} replaced,{' '}
          {needsAttention.length} need attention.
        </p>
      )}

      <h3>
        Images ({images.length}){missing.length > 0 && ` — ${missing.length} cars still missing one`}
      </h3>
      <div className="image-grid">
        {images.map((img) => (
          <figure key={img.id} className="car-image">
            <img
              src={`/api/car-images/${img.id}/data?v=${Date.parse(img.uploadedAt)}`}
              alt={`Car ${img.carNumber}`}
              loading="lazy"
            />
            <figcaption>#{img.carNumber}</figcaption>
          </figure>
        ))}
      </div>

      {missing.length > 0 && (
        <>
          <h3>Missing</h3>
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Class</th>
                <th>Team</th>
              </tr>
            </thead>
            <tbody>
              {missing.map((m) => (
                <tr key={m.carNumber}>
                  <td>{m.carNumber}</td>
                  <td>{m.className}</td>
                  <td>{m.teamName}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}
