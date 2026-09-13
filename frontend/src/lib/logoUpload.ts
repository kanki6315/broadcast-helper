import { imageRequest, resizeImage } from './carImageUpload'

export type LogoKind = 'MANUFACTURER' | 'SERIES' | 'DRIVER'
const types: Record<string, string> = { svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif' }

export async function uploadLogo(kind: LogoKind, target: string, file: File,
  progress: (message: string) => void): Promise<void> {
  const contentType = file.type || types[file.name.split('.').pop()?.toLowerCase() ?? '']
  if ((kind === 'DRIVER' && contentType === 'image/svg+xml') || !Object.values(types).includes(contentType)) throw new Error('Choose an SVG, PNG, JPEG, WebP, or GIF logo.')
  if (!file.size || file.size > 25 * 1024 * 1024) throw new Error('Choose a logo between 1 byte and 25 MB.')
  let data: Blob = file
  // Preserve vector logos.
  // New raster logos are sized on the device, with transparency retained.
  if (contentType !== 'image/svg+xml') {
    progress(kind === 'DRIVER' ? 'Resizing headshot…' : 'Resizing logo…')
    data = await resizeImage(file, kind === 'DRIVER' ? 640 : 1024)
  }
  progress(kind === 'DRIVER' ? 'Uploading headshot…' : 'Uploading logo…')
  const type = data === file ? contentType : data.type
  const plan = await imageRequest<{ id: string; uploadUrl: string }>('/api/logo-uploads', {
    kind, target, contentType: type, size: data.size,
  })
  const response = await fetch(plan.uploadUrl, {
    method: 'PUT', credentials: 'omit', headers: { 'Content-Type': type }, body: data,
    signal: AbortSignal.timeout(120_000),
  })
  if (!response.ok) throw new Error(`Logo upload failed (${response.status}). Please retry.`)
  progress(kind === 'DRIVER' ? 'Saving headshot…' : 'Saving logo…')
  await imageRequest(`/api/logo-uploads/${plan.id}/complete`, {})
}
