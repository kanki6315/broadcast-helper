import { useEffect, useState } from 'react'
import { imageRequest } from '../lib/carImageUpload'
import { migrateLogos } from '../lib/logoUpload'
import { useIsAdmin } from '../lib/auth'

/** One file per request keeps legacy migration memory bounded and retries resumable. */
export function PublicFileMigration({ eventId, driverId }: { eventId?: number; driverId?: number }) {
  const isAdmin = useIsAdmin()
  const [enabled, setEnabled] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  useEffect(() => {
    if (isAdmin) void imageRequest<{ directUpload: boolean }>('/api/car-images/uploads/config')
      .then((c) => setEnabled(c.directUpload)).catch(() => {})
  }, [isAdmin])
  if (!enabled || !isAdmin) return null
  async function migrate() {
    setBusy(true); setMessage('Moving files…')
    try {
      if (driverId !== undefined) await migrateLogos('DRIVER', setMessage, String(driverId))
      if (eventId !== undefined) {
        const files = await imageRequest<{ id: number; filename: string }[]>(`/api/document-storage/legacy?eventId=${eventId}`)
        for (const file of files) {
          setMessage(`Moving ${file.filename ?? 'PDF'}…`)
          const response = await fetch(`/api/document-storage/${file.id}/migrate`, { method: 'POST' })
          if (!response.ok) throw new Error(`Could not move ${file.filename ?? 'PDF'} (${response.status}). Retry to resume.`)
        }
      }
      window.location.reload()
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not move files. Retry to resume.') }
    finally { setBusy(false) }
  }
  return <div>
    <button type="button" disabled={busy} onClick={() => void migrate()}>
      {driverId !== undefined ? 'Move existing headshot to public storage' : 'Move existing PDFs to public storage'}
    </button>
    {message && <p role="status">{message}</p>}
  </div>
}
