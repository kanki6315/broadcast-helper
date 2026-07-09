import { useEffect, useRef, useState } from 'react'

interface ImportBatch {
  id: number
  kind: string
  filename: string
  status: string
  summary: string | null
  createdAt: string
}

export default function ImportsPage() {
  const [batches, setBatches] = useState<ImportBatch[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  async function loadBatches() {
    const res = await fetch('/api/imports')
    if (res.ok) setBatches(await res.json())
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

  async function act(id: number, action: 'commit' | 'discard') {
    setBusy(true)
    setError(null)
    const res = await fetch(`/api/imports/${id}/${action}`, { method: 'POST' })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(`Batch ${id}: ${body?.message ?? `${action} failed (${res.status})`}`)
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
            {batches.map((b) => (
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
                <td>{b.summary}</td>
                <td>{b.status}</td>
                <td>
                  {b.status === 'STAGED' && (
                    <>
                      <button disabled={busy} onClick={() => act(b.id, 'commit')}>
                        Commit
                      </button>{' '}
                      <button disabled={busy} onClick={() => act(b.id, 'discard')}>
                        Discard
                      </button>
                    </>
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
