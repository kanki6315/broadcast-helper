import { useCallback, useEffect, useRef, useState } from 'react'
import type { PDFDocumentProxy } from 'pdfjs-dist'
import './team-sheets-modal.css'

// pdf.js is ~400 kB minified — load it (and its worker) only when a sheet
// page actually has team sheets, not in the app bundle.
let pdfjsPromise: Promise<typeof import('pdfjs-dist')> | null = null

function loadPdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
    ]).then(([lib, worker]) => {
      lib.GlobalWorkerOptions.workerSrc = worker.default
      return lib
    })
  }
  return pdfjsPromise
}

// One loaded document per URL for the lifetime of the sheet page: the version
// query param changes when the PDF is replaced, so staleness takes care of
// itself, and re-opening the modal (or prefetching) never re-downloads.
const docCache = new Map<string, Promise<PDFDocumentProxy>>()

function loadDoc(url: string): Promise<PDFDocumentProxy> {
  let doc = docCache.get(url)
  if (!doc) {
    doc = loadPdfjs().then((pdfjs) => pdfjs.getDocument({ url }).promise)
    docCache.set(url, doc)
  }
  return doc
}

/** Warm the cache so the first row click opens without a spinner. */
export function prefetchTeamSheets(url: string) {
  void loadDoc(url).catch(() => docCache.delete(url))
}

/** Width the PDF pages are laid out at, in CSS px (canvas backing scales by DPR). */
const PAGE_WIDTH = 760

/** How far beyond the viewport pages stay rasterised, in px of scroll. */
const RENDER_MARGIN = 800

/**
 * One page slot. The placeholder always has the true page height (via
 * `ratio`), so scroll offsets are exact whether or not the page is rendered;
 * the canvas exists only while `active` — a 64-page document fully rasterised
 * would hold hundreds of MB of canvas backing.
 */
function PdfPage({
  doc,
  pageNo,
  ratio,
  active,
}: {
  doc: PDFDocumentProxy
  pageNo: number
  ratio: number
  active: boolean
}) {
  const holderRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const holder = holderRef.current
    if (!holder) return
    if (!active) {
      holder.replaceChildren()
      return
    }
    let cancelled = false
    void doc
      .getPage(pageNo)
      .then(async (page) => {
        if (cancelled) return
        const dpr = window.devicePixelRatio || 1
        const scale = (PAGE_WIDTH / page.getViewport({ scale: 1 }).width) * dpr
        const viewport = page.getViewport({ scale })
        const canvas = document.createElement('canvas')
        canvas.width = viewport.width
        canvas.height = viewport.height
        await page.render({ canvas, viewport }).promise
        if (!cancelled) holder.replaceChildren(canvas)
      })
      .catch(() => {
        /* a failed page keeps its blank placeholder */
      })
    return () => {
      cancelled = true
    }
  }, [active, doc, pageNo])

  return (
    <div
      ref={holderRef}
      className="ts-page"
      data-page={pageNo}
      style={{ aspectRatio: `${1 / ratio}` }}
    />
  )
}

export default function TeamSheetsModal({
  url,
  page,
  title,
  onClose,
}: {
  url: string
  page: number
  title: string
  onClose: () => void
}) {
  const [doc, setDoc] = useState<PDFDocumentProxy | null>(null)
  const [ratio, setRatio] = useState<number | null>(null) // page height / width
  const [error, setError] = useState<string | null>(null)
  const [activePages, setActivePages] = useState<ReadonlySet<number>>(new Set())
  const pagesRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    loadDoc(url)
      .then(async (d) => {
        const first = await d.getPage(1)
        if (cancelled) return
        const vp = first.getViewport({ scale: 1 })
        setRatio(vp.height / vp.width)
        setDoc(d)
      })
      .catch((e) => {
        docCache.delete(url)
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load the team sheets PDF')
      })
    return () => {
      cancelled = true
    }
  }, [url])

  // Pages within RENDER_MARGIN of the viewport get a canvas; the rest revert
  // to placeholders. Driven by the container's scroll position rather than an
  // IntersectionObserver so rendering is deterministic.
  const recomputeActive = useCallback(() => {
    const scroller = pagesRef.current
    if (!scroller) return
    const top = scroller.scrollTop - RENDER_MARGIN
    const bottom = scroller.scrollTop + scroller.clientHeight + RENDER_MARGIN
    const next = new Set<number>()
    for (const el of scroller.querySelectorAll<HTMLElement>('.ts-page')) {
      if (el.offsetTop + el.offsetHeight >= top && el.offsetTop <= bottom) {
        next.add(Number(el.dataset.page))
      }
    }
    setActivePages((prev) =>
      prev.size === next.size && [...next].every((p) => prev.has(p)) ? prev : next,
    )
  }, [])

  // Placeholders have exact heights, so jumping is precise before any render.
  useEffect(() => {
    if (!doc || ratio == null) return
    pagesRef.current
      ?.querySelector(`[data-page="${page}"]`)
      ?.scrollIntoView({ block: 'start' })
    recomputeActive()
  }, [doc, ratio, page, recomputeActive])

  useEffect(() => {
    window.addEventListener('resize', recomputeActive)
    return () => window.removeEventListener('resize', recomputeActive)
  }, [recomputeActive])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
    }
  }, [onClose])

  return (
    <div className="ts-overlay no-print" onClick={onClose}>
      <div className="ts-dialog" onClick={(e) => e.stopPropagation()}>
        <header className="ts-header">
          <span className="ts-title">{title}</span>
          <button className="ts-close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>
        <div className="ts-pages" ref={pagesRef} onScroll={recomputeActive}>
          {error && <p className="ts-status error">{error}</p>}
          {!error && (!doc || ratio == null) && <p className="ts-status">Loading team sheets…</p>}
          {doc &&
            ratio != null &&
            Array.from({ length: doc.numPages }, (_, i) => (
              <PdfPage
                key={i + 1}
                doc={doc}
                pageNo={i + 1}
                ratio={ratio}
                active={activePages.has(i + 1)}
              />
            ))}
        </div>
      </div>
    </div>
  )
}
