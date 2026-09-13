import { imageRequest, uploadCarImage } from './carImageUpload'
import { uploadLogo, type LogoKind } from './logoUpload'

interface Inventory {
  enabled: boolean
  photos: { id: number; seasonId: number; carNumber: string; filename: string | null; contentType: string; uploadedAt: string; originalUrl: string }[]
  logos: { kind: LogoKind; asset: { target: string; logoUrl: string; contentType: string; uploadedAt: string } }[]
  documents: { id: number; filename: string | null }[]
}
export interface MigrationProgress { completed: number; total: number; message: string }

async function readFile(url: string, name: string, type: string): Promise<File> {
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) })
  if (!response.ok) throw new Error(`Could not read file (${response.status})`)
  return new File([await response.blob()], name, { type })
}

export async function migratePublicStorage(progress: (value: MigrationProgress) => void) {
  const inventory = await imageRequest<Inventory>('/api/public-storage/migration', {})
  if (!inventory.enabled) throw new Error('Public storage is disabled on the server. Set R2_IMAGES_ENABLED=true and redeploy.')
  const total = inventory.photos.length + inventory.logos.length + inventory.documents.length
  let completed = 0
  const failures: string[] = []
  const report = (message: string) => progress({ completed, total, message })
  async function run(label: string, action: () => Promise<unknown>) {
    report(label)
    try { await action() }
    catch (error) { failures.push(`${label}: ${error instanceof Error ? error.message : 'Migration failed'}`) }
    finally { completed++; report(label) }
  }
  // Sequential files bound memory. A fresh inventory on retry skips all successful moves.
  for (const photo of inventory.photos) {
    await run(`Car #${photo.carNumber} (season ${photo.seasonId})`, async () => {
      const file = await readFile(photo.originalUrl, photo.filename ?? `${photo.carNumber}.jpg`, photo.contentType)
      await uploadCarImage(photo.seasonId, photo.carNumber, file, report, { migrationImageId: photo.id, sourceUploadedAt: photo.uploadedAt })
    })
  }
  for (const { kind, asset } of inventory.logos) {
    await run(`${kind === 'DRIVER' ? 'Headshot' : kind === 'SERIES' ? 'Series logo' : 'Manufacturer logo'} ${asset.target}`, async () => {
      const file = await readFile(asset.logoUrl, 'image', asset.contentType)
      await uploadLogo(kind, asset.target, file, report, asset.uploadedAt)
    })
  }
  for (const document of inventory.documents) {
    await run(document.filename ?? `PDF ${document.id}`, async () => {
      const response = await fetch(`/api/document-storage/${document.id}/migrate`, { method: 'POST', signal: AbortSignal.timeout(120_000) })
      if (!response.ok) {
        const error = await response.json().catch(() => null)
        throw new Error(error?.message ?? `PDF migration failed (${response.status})`)
      }
    })
  }
  report(total === 0 ? 'All existing files are already in public storage.' : `${total - failures.length} of ${total} files moved.`)
  return failures
}
