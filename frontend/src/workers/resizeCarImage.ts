// One worker per file. Terminating it releases decoded pixels before the next upload.
self.onmessage = async ({ data }: MessageEvent<File | { file: File; maxSize: number }>) => {
  const file = data instanceof File ? data : data.file
  const maxSize = data instanceof File ? 400 : data.maxSize
  let bitmap: ImageBitmap | undefined
  try {
    bitmap = await createImageBitmap(file)
    const scale = Math.min(1, maxSize / Math.max(bitmap.width, bitmap.height))
    const canvas = new OffscreenCanvas(Math.max(1, Math.round(bitmap.width * scale)), Math.max(1, Math.round(bitmap.height * scale)))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('Image resizing is unavailable in this browser.')
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    bitmap = undefined
    const blob = await canvas.convertToBlob({ type: 'image/webp', quality: 0.85 })
    if (blob.type !== 'image/webp') throw new Error('This browser cannot create WebP images. Try a current browser.')
    self.postMessage({ blob })
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : 'Could not resize this image.' })
  } finally {
    bitmap?.close()
  }
}
