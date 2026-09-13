export interface ImageMatch {
  filename: string
  carNumber: string | null
  status: 'MATCHED' | 'UNMATCHED' | 'AMBIGUOUS'
  candidates: string[]
}

export async function imageRequest<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(url, body === undefined ? undefined : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new Error(error?.message ?? `Image request failed (${response.status})`)
  }
  return response.json() as Promise<T>
}

export function resizeImage(file: File, maxSize = 400): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('../workers/resizeCarImage.ts', import.meta.url), { type: 'module' })
    const finish = () => { clearTimeout(timeout); worker.terminate() }
    const timeout = setTimeout(() => { finish(); reject(new Error('Image resizing timed out. Try a smaller image.')) }, 60_000)
    worker.onmessage = ({ data }: MessageEvent<{ blob?: Blob; error?: string }>) => {
      finish()
      if (data.blob) resolve(data.blob)
      else reject(new Error(data.error ?? 'Could not resize this image.'))
    }
    worker.onerror = () => { finish(); reject(new Error('Could not resize this image. Try a current browser.')) }
    worker.postMessage({ file, maxSize })
  })
}

/** Original and thumbnail go straight to R2. The API only receives JSON metadata. */
export async function uploadCarImage(seasonId: number, carNumber: string, file: File,
  progress: (message: string) => void): Promise<{ id: number; replaced: boolean }> {
  const types: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif' }
  const type = file.type || types[file.name.split('.').pop()?.toLowerCase() ?? '']
  if (!Object.values(types).includes(type)) throw new Error('Choose a JPEG, PNG, WebP, or GIF image.')
  if (file.size === 0 || file.size > 25 * 1024 * 1024) throw new Error('Choose an image between 1 byte and 25 MB.')
  progress(`Resizing ${file.name}…`)
  const sheet = await resizeImage(file)
  if (sheet.size > 1024 * 1024) throw new Error('The resized image is too large. Try another image.')
  const plan = await imageRequest<{ id: string; originalUrl: string; sheetUrl: string }>('/api/car-images/uploads', {
    seasonId, carNumber, filename: file.name, contentType: type, originalSize: file.size, sheetSize: sheet.size,
  })
  progress(`Uploading ${file.name}…`)
  // Sequential PUTs and sequential files bound memory and network pressure on the device.
  for (const [url, blob, contentType] of [[plan.originalUrl, file, type], [plan.sheetUrl, sheet, 'image/webp']] as const) {
    const response = await fetch(url, {
      method: 'PUT', credentials: 'omit', headers: { 'Content-Type': contentType }, body: blob,
      signal: AbortSignal.timeout(120_000),
    })
    if (!response.ok) throw new Error(`Could not upload ${file.name} (${response.status}). Please retry.`)
  }
  progress(`Saving ${file.name}…`)
  return imageRequest(`/api/car-images/uploads/${plan.id}/complete`, {})
}
