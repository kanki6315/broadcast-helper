import { useEffect, useRef, useState } from 'react'

interface ImportBatch {
  id: number
  kind: string
  filename: string
  status: string
  summary: string | null
  createdAt: string
}

interface ClassReview {
  knownClasses: string[]
  unknownClasses: string[]
}

export default function ImportsPage() {
  const [batches, setBatches] = useState<ImportBatch[]>([])
  // Per-batch class review (unrecognized classes needing a manual mapping).
  const [reviews, setReviews] = useState<Record<number, ClassReview>>({})
  // Per-batch chosen mapping: source class spelling -> canonical class.
  const [mappings, setMappings] = useState<Record<number, Record<string, string>>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  async function loadBatches() {
    const res = await fetch('/api/imports')
    if (!res.ok) return
    const list: ImportBatch[] = await res.json()
    setBatches(list)

    // Fetch class review for each staged results/standings batch.
    const staged = list.filter(
      (b) => b.status === 'STAGED' && (b.kind === 'RACE_RESULTS' || b.kind === 'STANDINGS'),
    )
    const entries = await Promise.all(
      staged.map(async (b) => {
        const r = await fetch(`/api/imports/${b.id}/class-review`)
        return [b.id, r.ok ? ((await r.json()) as ClassReview) : null] as const
      }),
    )
    const next: Record<number, ClassReview> = {}
    for (const [id, review] of entries) if (review) next[id] = review
    setReviews(next)
  }

  useEffect(() => {
    void loadBatches()
  }, [])

  async function uploadFiles(files: FileList) {
    setBusy(true)
    setError(null)
    for (const file of Array.from(files)) {
      const form = new FormData()
      form.append('file', file)
      const res = await fetch('/api/imports', { method: 'POST', body: form })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        setError(`${file.name}: ${body?.message ?? `upload failed (${res.status})`}`)
      }
    }
    if (fileInput.current) fileInput.current.value = ''
    await loadBatches()
    setBusy(false)
  }

  function setMapping(batchId: number, sourceClass: string, canonical: string) {
    setMappings((m) => ({ ...m, [batchId]: { ...m[batchId], [sourceClass]: canonical } }))
  }

  function unresolved(batchId: number): string[] {
    const unknown = reviews[batchId]?.unknownClasses ?? []
    const chosen = mappings[batchId] ?? {}
    return unknown.filter((c) => !chosen[c])
  }

  async function commit(id: number) {
    if (unresolved(id).length > 0) return
    setBusy(true)
    setError(null)
    const res = await fetch(`/api/imports/${id}/commit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ classMapping: mappings[id] ?? {} }),
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(`Batch ${id}: ${body?.message ?? `commit failed (${res.status})`}`)
    }
    await loadBatches()
    setBusy(false)
  }

  async function discard(id: number) {
    setBusy(true)
    setError(null)
    const res = await fetch(`/api/imports/${id}/discard`, { method: 'POST' })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(`Batch ${id}: ${body?.message ?? `discard failed (${res.status})`}`)
    }
    await loadBatches()
    setBusy(false)
  }

  return (
    <section>
      <p>
        Upload results/standings JSON files or an entry list PDF. Each file is staged for review —
        nothing touches the database until you commit it.
      </p>
      <input
        ref={fileInput}
        type="file"
        accept=".json,.pdf,application/json,application/pdf"
        multiple
        disabled={busy}
        onChange={(e) => e.target.files && uploadFiles(e.target.files)}
      />

      {error && <p className="error">{error}</p>}

      {batches.length === 0 ? (
        <p>No imports yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>File</th>
              <th>Kind</th>
              <th>Summary</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {batches.map((b) => {
              const unknown = reviews[b.id]?.unknownClasses ?? []
              const known = reviews[b.id]?.knownClasses ?? []
              const blocked = unresolved(b.id).length > 0
              return (
                <tr key={b.id}>
                  <td>{b.id}</td>
                  <td>{b.filename}</td>
                  <td>
                    {b.kind === 'RACE_RESULTS'
                      ? 'Results'
                      : b.kind === 'ENTRY_LIST'
                        ? 'Entry list'
                        : 'Standings'}
                  </td>
                  <td>
                    {b.summary}
                    {b.status === 'STAGED' && unknown.length > 0 && (
                      <div className="class-review">
                        <strong>Unrecognized class{unknown.length > 1 ? 'es' : ''}</strong> — map to a
                        known class before committing:
                        {unknown.map((c) => (
                          <label key={c} className="class-map-row">
                            <span className="class-map-source">{c}</span> →{' '}
                            <select
                              value={mappings[b.id]?.[c] ?? ''}
                              disabled={busy}
                              onChange={(e) => setMapping(b.id, c, e.target.value)}
                            >
                              <option value="" disabled>
                                choose…
                              </option>
                              {known.map((k) => (
                                <option key={k} value={k}>
                                  {k}
                                </option>
                              ))}
                            </select>
                          </label>
                        ))}
                      </div>
                    )}
                  </td>
                  <td>{b.status}</td>
                  <td>
                    {b.status === 'STAGED' && (
                      <>
                        <button
                          disabled={busy || blocked}
                          title={blocked ? 'Map every unrecognized class first' : undefined}
                          onClick={() => commit(b.id)}
                        >
                          Commit
                        </button>{' '}
                        <button disabled={busy} onClick={() => discard(b.id)}>
                          Discard
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
