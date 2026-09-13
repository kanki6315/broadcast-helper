import { imageRequest, resizeImage } from './carImageUpload'

export type LogoKind = 'MANUFACTURER' | 'SERIES' | 'DRIVER'
interface LogoAsset {
  target: string
  contentType: string
  uploadedAt: string
  logoUrl: string
  publicStorage: boolean
}
const types: Record<string, string> = { svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif' }

export async function uploadLogo(kind: LogoKind, target: string, file: File,
  progress: (message: string) => void, sourceUploadedAt?: string): Promise<void> {
  const { directUpload } = await imageRequest<{ directUpload: boolean }>('/api/car-images/uploads/config')
  if (!directUpload) {
    if (sourceUploadedAt) throw new Error('Public image storage is not enabled.')
    const form = new FormData(); form.append('file', file)
    const url = kind === 'DRIVER' ? `/api/drivers/${encodeURIComponent(target)}/photo` : kind === 'SERIES' ? `/api/series/${encodeURIComponent(target)}/logo`
      : `/api/manufacturer-logos?name=${encodeURIComponent(target)}`
    const response = await fetch(url, { method: 'POST', body: form })
    if (!response.ok) {
      const error = await response.json().catch(() => null)
      throw new Error(error?.message ?? `Logo upload failed (${response.status})`)
    }
    return
  }
  const contentType = file.type || types[file.name.split('.').pop()?.toLowerCase() ?? '']
  if ((kind === 'DRIVER' && contentType === 'image/svg+xml') || !Object.values(types).includes(contentType)) throw new Error('Choose an SVG, PNG, JPEG, WebP, or GIF logo.')
  if (!file.size || file.size > 25 * 1024 * 1024) throw new Error('Choose a logo between 1 byte and 25 MB.')
  let data: Blob = file
  // Preserve vector logos, and migrate existing logos byte-for-byte for rollback.
  // New raster logos are sized on the device, with transparency retained.
  if (contentType !== 'image/svg+xml' && !sourceUploadedAt) {
    progress(kind === 'DRIVER' ? 'Resizing headshot…' : 'Resizing logo…')
    data = await resizeImage(file, kind === 'DRIVER' ? 640 : 1024)
  }
  progress(kind === 'DRIVER' ? 'Uploading headshot…' : 'Uploading logo…')
  const type = data === file ? contentType : data.type
  const plan = await imageRequest<{ id: string; uploadUrl: string }>('/api/logo-uploads', {
    kind, target, contentType: type, size: data.size, sourceUploadedAt,
  })
  const response = await fetch(plan.uploadUrl, {
    method: 'PUT', credentials: 'omit', headers: { 'Content-Type': type }, body: data,
    signal: AbortSignal.timeout(120_000),
  })
  if (!response.ok) throw new Error(`Logo upload failed (${response.status}). Please retry.`)
  progress(kind === 'DRIVER' ? 'Saving headshot…' : 'Saving logo…')
  await imageRequest(`/api/logo-uploads/${plan.id}/complete`, {})
}

/** Resume migration safely; new logo bytes never pass back through the API. */
export async function migrateLogos(kind: LogoKind, progress: (message: string) => void, target?: string): Promise<void> {
  const assets = await imageRequest<LogoAsset[]>(`/api/logo-uploads/assets?kind=${kind}`)
  const failures: string[] = []
  for (const asset of assets.filter((a) => !a.publicStorage && (target === undefined || a.target === target))) {
    try {
      progress(`Reading ${kind === 'DRIVER' ? 'headshot' : 'logo'} ${asset.target}…`)
      const response = await fetch(asset.logoUrl, { signal: AbortSignal.timeout(120_000) })
      if (!response.ok) throw new Error(`Could not read the logo (${response.status}).`)
      const file = new File([await response.blob()], 'logo', { type: asset.contentType })
      await uploadLogo(kind, asset.target, file, progress, asset.uploadedAt)
    } catch (error) {
      failures.push(`${asset.target}: ${error instanceof Error ? error.message : 'Could not move logo.'}`)
    }
  }
  if (failures.length) throw new Error(failures.join(' '))
}
