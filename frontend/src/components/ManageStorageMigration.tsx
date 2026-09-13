import { useEffect, useState } from 'react'
import { imageRequest } from '../lib/carImageUpload'
import { migratePublicStorage, type MigrationProgress } from '../lib/migratePublicStorage'

export default function ManageStorageMigration() {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<MigrationProgress | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  useEffect(() => {
    void imageRequest<{ directUpload: boolean }>('/api/car-images/uploads/config')
      .then(c => setEnabled(c.directUpload))
      .catch(error => setErrors([`Could not check public storage: ${error.message}. Refresh to retry.`]))
  }, [])
  useEffect(() => {
    if (!busy) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [busy])
  async function migrate() {
    setBusy(true); setErrors([]); setProgress({ completed: 0, total: 0, message: 'Finding existing files…' })
    try { setErrors(await migratePublicStorage(setProgress)) }
    catch (error) { setErrors([error instanceof Error ? error.message : 'Migration failed. Retry to resume.']) }
    finally { setBusy(false) }
  }
  return <section className="settings-section" aria-label="Public file storage">
    <h2>Public file storage</h2>
    <p>Move existing car photos, logos, driver headshots and PDFs to public storage across all seasons and events. Existing database copies are retained.</p>
    <button className="btn" type="button" disabled={busy || enabled !== true} onClick={() => void migrate()}>
      {busy ? 'Moving files…' : errors.length && enabled ? 'Retry migration' : 'Move all existing files to public storage'}
    </button>
    {enabled === null && !errors.length && <p role="status">Checking public storage…</p>}
    {enabled === false && <p>Public storage is disabled on the server. Set R2_IMAGES_ENABLED=true in Railway and deploy the change.</p>}
    {busy && <p>Keep this page open until finished. Files move one at a time.</p>}
    {progress && <p role="status">{busy && progress.total > 0 ? `${progress.completed} / ${progress.total} checked · ` : ''}{progress.message}</p>}
    {errors.length > 0 && <div role="alert"><p>Completed files will be skipped when you retry.</p><ul>{errors.map((error, index) => <li key={index}>{error}</li>)}</ul></div>}
  </section>
}
