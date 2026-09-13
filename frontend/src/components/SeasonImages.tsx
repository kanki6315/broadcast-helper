import { useCallback, useEffect, useRef, useState } from 'react'
import { useIsAdmin } from '../lib/auth'
import { imageRequest, uploadCarImage, type ImageMatch } from '../lib/carImageUpload'

interface ImageSummary {
  id: number
  carNumber: string
  sourceFilename: string | null
  uploadedAt: string
  imageUrl: string
  originalUrl: string
  publicStorage: boolean
}

interface MissingCar {
  carNumber: string
  className: string
  teamName: string
}

interface BulkResult {
  filename: string
  status: 'MATCHED' | 'REPLACED' | 'UNMATCHED' | 'AMBIGUOUS' | 'FAILED'
  carNumber: string | null
  candidates: string[]
  message?: string
}

/** Per-season car-image management, embedded in the season hub. */
export default function SeasonImages({ seasonId }: { seasonId: number }) {
  const isAdmin = useIsAdmin()
  const [images, setImages] = useState<ImageSummary[]>([])
  const [missing, setMissing] = useState<MissingCar[]>([])
  const [results, setResults] = useState<BulkResult[]>([])
  const [assignments, setAssignments] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState('')
  // Original File objects by name, so unmatched files can be assigned manually
  // without re-selecting them.
  const pendingFiles = useRef<Map<string, File>>(new Map())
  const fileInput = useRef<HTMLInputElement>(null)

  const loadOverview = useCallback(async () => {
    const res = await fetch(`/api/car-images?seasonId=${seasonId}`)
    if (res.ok) {
      const data = await res.json()
      setImages(data.images)
      setMissing(data.missing)
    }
  }, [seasonId])

  useEffect(() => {
    void loadOverview()
  }, [loadOverview])

  async function uploadFiles(files: FileList) {
    setBusy(true)
    setError(null)
    setResults([])
    const selected = Array.from(files)
    pendingFiles.current = new Map(selected.map((f) => [f.name, f]))
    try {
      const matches = await imageRequest<ImageMatch[]>('/api/car-images/uploads/match', {
        seasonId, filenames: selected.map((file) => file.name),
      })
      for (let i = 0; i < selected.length; i++) {
        const file = selected[i]
        const match = matches[i]
        let result: BulkResult
        if (!match.carNumber) {
          result = match
        } else {
          try {
            const saved = await uploadCarImage(seasonId, match.carNumber, file, setProgress)
            result = { ...match, status: saved.replaced ? 'REPLACED' : 'MATCHED' }
            pendingFiles.current.delete(file.name)
          } catch (e) {
            result = { ...match, status: 'FAILED', message: e instanceof Error ? e.message : 'Upload failed.' }
            setAssignments(a => ({ ...a, [file.name]: match.carNumber! }))
          }
        }
        setResults(rs => [...rs, result])
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Upload failed.')
    } finally {
      if (fileInput.current) fileInput.current.value = ''
      setBusy(false)
      setProgress('')
      await loadOverview().catch(() => setError('Could not refresh photos. Reload the page.'))
    }
  }

  async function assign(filename: string) {
    const number = (assignments[filename] ?? '').trim()
    const file = pendingFiles.current.get(filename)
    if (!number || !file) return
    setBusy(true)
    setError(null)
    try {
      await uploadCarImage(seasonId, number, file, setProgress)
      pendingFiles.current.delete(filename)
      setResults((rs) => rs.map((r) => r.filename === filename ? { ...r, status: 'MATCHED', carNumber: number, message: undefined } : r))
      await loadOverview()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Upload failed.')
    } finally {
      setBusy(false)
      setProgress('')
    }
  }


  const needsAttention = results.filter((r) => r.status === 'UNMATCHED' || r.status === 'AMBIGUOUS' || r.status === 'FAILED')

  return (
    <div>
      {isAdmin && (
        <>
          <p>
            Bulk-upload car photos named with the car number (e.g. <code>31.png</code>,{' '}
            <code>2026_023_triarsi.jpg</code>). Images are matched per season, so shared numbers
            across series never collide, and each image carries over between events until replaced.
          </p>

          <div className="series-form">
            <input
              ref={fileInput}
              type="file"
              accept="image/*"
              multiple
              disabled={busy}
              onChange={(e) => e.target.files && uploadFiles(e.target.files)}
            />
          </div>
        </>
      )}

      {busy && <p role="status">{progress || 'Preparing photos…'}</p>}
      {error && <p className="error">{error}</p>}

      {needsAttention.length > 0 && (
        <>
          <h4>Needs attention</h4>
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
                    {r.status === 'FAILED' ? r.message : r.status === 'AMBIGUOUS'
                      ? `Matches several cars: ${r.candidates.join(', ')}`
                      : 'No known car number in filename'}
                  </td>
                  <td>
                    <div className="alias-form">
                      <input
                        value={assignments[r.filename] ?? ''}
                        onChange={(e) => setAssignments((a) => ({ ...a, [r.filename]: e.target.value }))}
                        placeholder="e.g. 023"
                      />
                      <button disabled={busy} onClick={() => assign(r.filename)}>
                        {r.status === 'FAILED' ? 'Retry' : 'Assign'}
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

      <h4>
        Images ({images.length}){missing.length > 0 && ` — ${missing.length} cars still missing one`}
      </h4>
      <div className="image-grid">
        {images.map((img) => (
          <figure key={img.id} className="car-image">
            <img
              src={img.imageUrl}
              alt={`Car ${img.carNumber}`}
              loading="lazy"
            />
            <figcaption>#{img.carNumber}</figcaption>
          </figure>
        ))}
      </div>

      {missing.length > 0 && (
        <>
          <h4>Missing</h4>
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
    </div>
  )
}
