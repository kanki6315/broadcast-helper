import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import './scratchpad-modal.css'
import { getJson } from '../lib/api'
import {
  PAD_WIDTH,
  TILE_HEIGHT,
  actionStrokes,
  applyRedo,
  applyUndo,
  eraserHits,
  strokeBBox,
  strokeIntersectsTile,
  thinAppend,
  tileCount,
  tilesForBBox,
  traceStroke,
  type PadAction,
  type Stroke,
} from '../lib/scratchpad'

interface PadResponse {
  eventId: number
  revision: number
  pageHeight: number
  strokes: Stroke[]
}

/** Literal ink colours: the pad is white paper in both themes, so persisted
 *  strokes never depend on the app theme. */
const COLORS = [
  { value: '#111827', name: 'Black' },
  { value: '#dc2626', name: 'Red' },
  { value: '#2563eb', name: 'Blue' },
  { value: '#16a34a', name: 'Green' },
  { value: '#ea580c', name: 'Orange' },
  { value: '#9333ea', name: 'Purple' },
]
const SIZES = [
  { label: 'S', value: 2 },
  { label: 'M', value: 4 },
  { label: 'L', value: 8 },
]
const ERASER_RADIUS = 12
/** How far beyond the viewport tiles keep a live canvas, in px of scroll. */
const RENDER_MARGIN = 800
const SAVE_DEBOUNCE_MS = 2500
const MAX_ACTIONS = 200
const PAGE_EXTEND_STEP = 1000
const MAX_PAGE_HEIGHT = 50000

type Phase = 'loading' | 'error' | 'ready'
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error' | 'full' | 'conflict'

/** Backing stores cost width × height × dpr² × 4 bytes; 2× is visually
 *  indistinguishable from 3× at pen widths and keeps tile memory bounded. */
function dpr(): number {
  return Math.min(window.devicePixelRatio || 1, 2)
}

export default function ScratchpadModal({ eventId, onClose }: { eventId: number; onClose: () => void }) {
  const [phase, setPhase] = useState<Phase>('loading')
  const [tool, setTool] = useState<'pen' | 'eraser'>('pen')
  const [color, setColor] = useState(COLORS[0].value)
  const [size, setSize] = useState(SIZES[1].value)
  const [pageHeight, setPageHeight] = useState(2000)
  const [activeTiles, setActiveTiles] = useState<ReadonlySet<number>>(new Set())
  const [status, setStatus] = useState<SaveStatus>('idle')
  // Undo/redo stacks live in refs; this counter re-renders the toolbar's
  // disabled states after each imperative mutation.
  const [, setActionCount] = useState(0)

  const scrollRef = useRef<HTMLDivElement>(null)
  const padRef = useRef<HTMLDivElement>(null)
  const canvasesRef = useRef(new Map<number, HTMLCanvasElement>())

  const strokesRef = useRef<Stroke[]>([])
  const undoRef = useRef<PadAction[]>([])
  const redoRef = useRef<PadAction[]>([])
  const drawingRef = useRef<{ stroke: Stroke; drawnPieces: number } | null>(null)
  const erasingRef = useRef<{
    removed: { index: number; stroke: Stroke }[]
    last: [number, number]
  } | null>(null)

  const revisionRef = useRef(0)
  const pageHeightRef = useRef(2000)
  const dirtyRef = useRef(false)
  const savingRef = useRef(false)
  const conflictRef = useRef(false)
  const debounceTimer = useRef<number | undefined>(undefined)
  const statusTimer = useRef<number | undefined>(undefined)

  /* ---- drawing ---------------------------------------------------------- */

  /** Context transformed so logical pad coordinates draw correctly into this
   *  tile's canvas, or null when the tile has no live canvas. */
  const tileCtx = useCallback((tile: number): CanvasRenderingContext2D | null => {
    const canvas = canvasesRef.current.get(tile)
    if (!canvas || canvas.width === 0) return null
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    const k = (canvas.width / canvas.clientWidth) * (canvas.clientWidth / PAD_WIDTH)
    ctx.setTransform(k, 0, 0, k, 0, -tile * TILE_HEIGHT * k)
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    return ctx
  }, [])

  const drawStroke = useCallback((ctx: CanvasRenderingContext2D, s: Stroke) => {
    ctx.strokeStyle = s.color
    ctx.lineWidth = s.size
    ctx.beginPath()
    traceStroke(ctx, s.points)
    ctx.stroke()
  }, [])

  /** Size the backing store to the laid-out CSS box and replay every stroke
   *  that crosses this tile. */
  const replayTile = useCallback(
    (tile: number) => {
      const canvas = canvasesRef.current.get(tile)
      if (!canvas) return
      const scale = dpr()
      const w = Math.round(canvas.clientWidth * scale)
      const h = Math.round(canvas.clientHeight * scale)
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w
        canvas.height = h
      }
      // A fresh canvas reports the spec default 300×150, so "needs sizing"
      // can't be read off width — mark sized canvases explicitly.
      canvas.dataset.ready = '1'
      const ctx = tileCtx(tile)
      if (!ctx) return
      ctx.save()
      ctx.setTransform(1, 0, 0, 1, 0, 0)
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      ctx.restore()
      for (const s of strokesRef.current) {
        if (strokeIntersectsTile(strokeBBox(s), tile)) drawStroke(ctx, s)
      }
    },
    [drawStroke, tileCtx],
  )

  const redrawForStrokes = useCallback(
    (strokes: Stroke[]) => {
      const tiles = new Set<number>()
      for (const s of strokes) {
        for (const t of tilesForBBox(strokeBBox(s), pageHeightRef.current)) tiles.add(t)
      }
      for (const t of tiles) {
        if (canvasesRef.current.has(t)) replayTile(t)
      }
    },
    [replayTile],
  )

  /** Draw only the curve pieces completed since the last call — nothing
   *  replays while the pen is down, so ink latency tracks the pointer.
   *  Piece j of traceStroke's path (control = point j, ending at the j/j+1
   *  midpoint) becomes drawable once point j+1 exists; the straight tail to
   *  the final point is painted by the commit-time replay. */
  const drawNewSegments = useCallback(
    (d: { stroke: Stroke; drawnPieces: number }) => {
      const p = d.stroke.points
      const count = p.length / 2
      const lastPiece = count - 2
      if (lastPiece <= d.drawnPieces) return
      const first = d.drawnPieces + 1
      let minY = Infinity
      let maxY = -Infinity
      for (let j = first - 1; j < count; j++) {
        const y = p[2 * j + 1]
        if (y < minY) minY = y
        if (y > maxY) maxY = y
      }
      const pad = d.stroke.size / 2 + 1
      for (const [tile] of canvasesRef.current) {
        if (maxY + pad < tile * TILE_HEIGHT || minY - pad > (tile + 1) * TILE_HEIGHT) continue
        const ctx = tileCtx(tile)
        if (!ctx) continue
        ctx.strokeStyle = d.stroke.color
        ctx.lineWidth = d.stroke.size
        ctx.beginPath()
        if (first === 1) {
          ctx.moveTo(p[0], p[1])
        } else {
          // Consecutive pieces share midpoint endpoints, so one chained path
          // starting at the previous piece's end covers the whole batch.
          ctx.moveTo((p[2 * first - 2] + p[2 * first]) / 2, (p[2 * first - 1] + p[2 * first + 1]) / 2)
        }
        for (let j = first; j <= lastPiece; j++) {
          ctx.quadraticCurveTo(
            p[2 * j],
            p[2 * j + 1],
            (p[2 * j] + p[2 * j + 2]) / 2,
            (p[2 * j + 1] + p[2 * j + 3]) / 2,
          )
        }
        ctx.stroke()
      }
      d.drawnPieces = lastPiece
    },
    [tileCtx],
  )

  /* ---- save ------------------------------------------------------------- */

  const showSaved = useCallback(() => {
    setStatus('saved')
    window.clearTimeout(statusTimer.current)
    statusTimer.current = window.setTimeout(() => setStatus('idle'), 1600)
  }, [])

  const flush = useCallback(() => {
    if (!dirtyRef.current || savingRef.current || conflictRef.current) return
    dirtyRef.current = false
    savingRef.current = true
    setStatus('saving')
    void fetch(`/api/events/${eventId}/scratchpad`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        baseRevision: revisionRef.current,
        pageHeight: pageHeightRef.current,
        strokes: strokesRef.current,
      }),
    })
      .then(async (r) => {
        savingRef.current = false
        if (r.status === 409) {
          conflictRef.current = true
          setStatus('conflict')
          return
        }
        if (r.status === 413) {
          dirtyRef.current = true
          setStatus('full')
          return
        }
        if (!r.ok) throw new Error(`Backend returned ${r.status}`)
        const body = (await r.json()) as { revision: number }
        revisionRef.current = body.revision
        if (dirtyRef.current) flushRef.current()
        else showSaved()
      })
      .catch(() => {
        savingRef.current = false
        dirtyRef.current = true
        setStatus('error')
      })
  }, [eventId, showSaved])

  // Stable identity for event listeners and the trailing-save recursion.
  const flushRef = useRef(flush)
  useEffect(() => {
    flushRef.current = flush
  }, [flush])

  const markDirty = useCallback(() => {
    dirtyRef.current = true
    window.clearTimeout(debounceTimer.current)
    debounceTimer.current = window.setTimeout(() => flushRef.current(), SAVE_DEBOUNCE_MS)
  }, [])

  /* ---- document mutations ----------------------------------------------- */

  const pushAction = useCallback(
    (action: PadAction) => {
      undoRef.current.push(action)
      if (undoRef.current.length > MAX_ACTIONS) undoRef.current.shift()
      redoRef.current = []
      setActionCount((n) => n + 1)
      markDirty()
    },
    [markDirty],
  )

  const undo = useCallback(() => {
    const action = undoRef.current.pop()
    if (!action) return
    applyUndo(strokesRef.current, action)
    redoRef.current.push(action)
    redrawForStrokes(actionStrokes(action))
    setActionCount((n) => n + 1)
    markDirty()
  }, [markDirty, redrawForStrokes])

  const redo = useCallback(() => {
    const action = redoRef.current.pop()
    if (!action) return
    applyRedo(strokesRef.current, action)
    undoRef.current.push(action)
    redrawForStrokes(actionStrokes(action))
    setActionCount((n) => n + 1)
    markDirty()
  }, [markDirty, redrawForStrokes])

  /* ---- pointer input ---------------------------------------------------- */

  const toLogical = useCallback((e: { clientX: number; clientY: number }): [number, number] => {
    const rect = padRef.current!.getBoundingClientRect()
    const k = PAD_WIDTH / rect.width
    // Tenth-of-a-px precision: whole-px rounding staircases slow handwriting.
    const x = Math.min(PAD_WIDTH, Math.max(0, Math.round((e.clientX - rect.left) * k * 10) / 10))
    const y = Math.min(pageHeightRef.current, Math.max(0, Math.round((e.clientY - rect.top) * k * 10) / 10))
    return [x, y]
  }, [])

  const eraseAt = useCallback(
    (x: number, y: number) => {
      const drag = erasingRef.current
      if (!drag) return
      const hits = eraserHits(strokesRef.current, x, y, ERASER_RADIUS)
      if (hits.length === 0) return
      const removedNow: Stroke[] = []
      // Highest index first so earlier splices don't shift later ones.
      for (let i = hits.length - 1; i >= 0; i--) {
        const [stroke] = strokesRef.current.splice(hits[i], 1)
        drag.removed.push({ index: hits[i], stroke })
        removedNow.push(stroke)
      }
      redrawForStrokes(removedNow)
    },
    [redrawForStrokes],
  )

  const onPointerDown = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      // Fingers scroll (touch-action: pan-y handles it natively); the Pencil
      // and the mouse's primary button draw.
      if (e.pointerType === 'touch') return
      if (e.pointerType === 'mouse' && e.button !== 0) return
      e.preventDefault()
      padRef.current!.setPointerCapture(e.pointerId)
      const [x, y] = toLogical(e)
      if (tool === 'eraser') {
        erasingRef.current = { removed: [], last: [x, y] }
        eraseAt(x, y)
      } else {
        drawingRef.current = { stroke: { tool: 'pen', color, size, points: [x, y] }, drawnPieces: 0 }
      }
    },
    [color, eraseAt, size, toLogical, tool],
  )

  const onPointerMove = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      const drawing = drawingRef.current
      const erasing = erasingRef.current
      if (!drawing && !erasing) return
      // Coalesced events carry the Pencil's full sample rate between frames.
      const samples = e.nativeEvent.getCoalescedEvents?.() ?? [e.nativeEvent]
      if (drawing) {
        for (const s of samples) {
          const [x, y] = toLogical(s)
          thinAppend(drawing.stroke.points, x, y)
        }
        drawNewSegments(drawing)
      } else if (erasing) {
        for (const s of samples) {
          const [x, y] = toLogical(s)
          // A fast wipe can jump well past the eraser radius between pointer
          // samples; walk the gap so the path can't skip over a stroke.
          const [lx, ly] = erasing.last
          const dist = Math.hypot(x - lx, y - ly)
          const steps = Math.max(1, Math.ceil(dist / ERASER_RADIUS))
          for (let i = 1; i <= steps; i++) {
            eraseAt(lx + ((x - lx) * i) / steps, ly + ((y - ly) * i) / steps)
          }
          erasing.last = [x, y]
        }
      }
    },
    [drawNewSegments, eraseAt, toLogical],
  )

  const onPointerEnd = useCallback(() => {
    const drawing = drawingRef.current
    if (drawing) {
      drawingRef.current = null
      strokesRef.current.push(drawing.stroke)
      // Replay the affected tiles: paints the curve's final straight tail
      // (and a tap's round-cap dot), which incremental drawing leaves out.
      redrawForStrokes([drawing.stroke])
      pushAction({ type: 'add', stroke: drawing.stroke })
    }
    const erasing = erasingRef.current
    if (erasing) {
      erasingRef.current = null
      if (erasing.removed.length > 0) pushAction({ type: 'erase', removed: erasing.removed })
    }
  }, [pushAction, redrawForStrokes])

  /* ---- tile windowing ---------------------------------------------------- */

  const recomputeActive = useCallback(() => {
    const scroller = scrollRef.current
    if (!scroller) return
    const top = scroller.scrollTop - RENDER_MARGIN
    const bottom = scroller.scrollTop + scroller.clientHeight + RENDER_MARGIN
    const next = new Set<number>()
    for (const el of scroller.querySelectorAll<HTMLElement>('.sp-tile')) {
      if (el.offsetTop + el.offsetHeight >= top && el.offsetTop <= bottom) {
        next.add(Number(el.dataset.tile))
      }
    }
    setActiveTiles((prev) =>
      prev.size === next.size && [...next].every((t) => prev.has(t)) ? prev : next,
    )
  }, [])

  const canvasRef = useCallback((tile: number, el: HTMLCanvasElement | null) => {
    if (el) canvasesRef.current.set(tile, el)
    else canvasesRef.current.delete(tile)
  }, [])

  // Newly-mounted canvases need sizing and a first paint. Keyed on phase as
  // well as the active set: a reload (conflict recovery) unmounts every tile
  // while activeTiles keeps its identity, so the remounted canvases would
  // otherwise never repaint.
  useEffect(() => {
    if (phase !== 'ready') return
    for (const tile of activeTiles) {
      const canvas = canvasesRef.current.get(tile)
      if (canvas && !canvas.dataset.ready) replayTile(tile)
    }
  }, [activeTiles, phase, replayTile])

  useEffect(() => {
    if (phase === 'ready') recomputeActive()
  }, [phase, pageHeight, recomputeActive])

  useEffect(() => {
    const onResize = () => {
      recomputeActive()
      // Laid-out sizes changed, so every live canvas needs a new backing store.
      for (const [tile] of canvasesRef.current) replayTile(tile)
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [recomputeActive, replayTile])

  /* ---- load / reload ----------------------------------------------------- */

  const load = useCallback(() => {
    setPhase('loading')
    getJson<PadResponse>(`/api/events/${eventId}/scratchpad`)
      .then((pad) => {
        strokesRef.current = pad.strokes
        revisionRef.current = pad.revision
        pageHeightRef.current = pad.pageHeight
        undoRef.current = []
        redoRef.current = []
        dirtyRef.current = false
        conflictRef.current = false
        setPageHeight(pad.pageHeight)
        setStatus('idle')
        setActionCount((n) => n + 1)
        // Tiles remount from scratch (phase went through 'loading'), and the
        // paint effect above repaints them once this lands.
        setPhase('ready')
      })
      .catch(() => setPhase('error'))
  }, [eventId])

  useEffect(() => {
    load()
  }, [load])

  /* ---- lifecycle: keys, scroll lock, flush-on-hide ------------------------ */

  const close = useCallback(() => {
    flushRef.current()
    onClose()
  }, [onClose])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        close()
      } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault()
        if (e.shiftKey) redo()
        else undo()
      }
    }
    document.addEventListener('keydown', onKey)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
    }
  }, [close, redo, undo])

  useEffect(() => {
    // pagehide is the signal iPad Safari actually delivers when the tab goes
    // away; the fetch is best-effort there, and the debounce bounds the loss
    // to ~2.5 s of ink.
    const onHidden = () => {
      if (document.visibilityState === 'hidden') flushRef.current()
    }
    const onPageHide = () => flushRef.current()
    document.addEventListener('visibilitychange', onHidden)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      document.removeEventListener('visibilitychange', onHidden)
      window.removeEventListener('pagehide', onPageHide)
      window.clearTimeout(debounceTimer.current)
      window.clearTimeout(statusTimer.current)
      flushRef.current()
    }
  }, [])

  // iPadOS lets the Pencil pan the page like a finger unless the stylus
  // touch is cancelled at the touch layer — touch-action alone can't
  // distinguish the two, so a non-passive listener does.
  useEffect(() => {
    const pad = padRef.current
    if (!pad || phase !== 'ready') return
    const guard = (e: TouchEvent) => {
      for (const t of Array.from(e.touches)) {
        if ((t as Touch & { touchType?: string }).touchType === 'stylus') {
          e.preventDefault()
          return
        }
      }
    }
    pad.addEventListener('touchmove', guard, { passive: false })
    return () => pad.removeEventListener('touchmove', guard)
  }, [phase])

  /* ---- render ------------------------------------------------------------ */

  const extendPage = useCallback(() => {
    const next = Math.min(MAX_PAGE_HEIGHT, pageHeightRef.current + PAGE_EXTEND_STEP)
    if (next === pageHeightRef.current) return
    pageHeightRef.current = next
    setPageHeight(next)
    markDirty()
  }, [markDirty])

  const tiles = tileCount(pageHeight)

  return (
    <div className="sp-overlay no-print" onClick={close}>
      <div className="sp-dialog" onClick={(e) => e.stopPropagation()}>
        <header className="sp-header">
          <span className="sp-title">Scratchpad</span>
          <span
            className={`sp-save-status${status === 'idle' ? '' : ' show'}${
              status === 'error' || status === 'full' ? ' error' : ''
            }`}
            role="status"
          >
            {status === 'saving' && 'Saving…'}
            {status === 'saved' && 'Saved'}
            {status === 'error' && 'Save failed — will retry'}
            {status === 'full' && 'Pad full — erase some strokes'}
            {status === 'conflict' && 'Changed elsewhere'}
          </span>
          <button className="sp-close" onClick={close} aria-label="Close">
            ✕
          </button>
        </header>

        <div className="sp-toolbar">
          <div className="seg" role="group" aria-label="Tool">
            <button
              type="button"
              className={`seg-btn${tool === 'pen' ? ' active' : ''}`}
              onClick={() => setTool('pen')}
            >
              Pen
            </button>
            <button
              type="button"
              className={`seg-btn${tool === 'eraser' ? ' active' : ''}`}
              onClick={() => setTool('eraser')}
            >
              Eraser
            </button>
          </div>
          <div className="sp-swatches" role="group" aria-label="Pen colour">
            {COLORS.map((c) => (
              <button
                key={c.value}
                type="button"
                className={`sp-swatch${c.value === color && tool === 'pen' ? ' active' : ''}`}
                style={{ background: c.value }}
                aria-label={c.name}
                aria-pressed={c.value === color}
                onClick={() => {
                  setColor(c.value)
                  setTool('pen')
                }}
              />
            ))}
          </div>
          <div className="seg" role="group" aria-label="Pen size">
            {SIZES.map((s) => (
              <button
                key={s.label}
                type="button"
                className={`seg-btn${s.value === size ? ' active' : ''}`}
                onClick={() => setSize(s.value)}
              >
                {s.label}
              </button>
            ))}
          </div>
          <div className="sp-toolbar-actions">
            <button className="btn" onClick={undo} disabled={undoRef.current.length === 0}>
              Undo
            </button>
            <button className="btn" onClick={redo} disabled={redoRef.current.length === 0}>
              Redo
            </button>
          </div>
        </div>

        {status === 'conflict' && (
          <div className="sp-conflict">
            This pad was changed in another tab or on another device — reload it to keep drawing.
            <button className="btn" onClick={load}>
              Reload
            </button>
          </div>
        )}

        <div className="sp-scroll" ref={scrollRef} onScroll={recomputeActive}>
          {phase === 'loading' && <p className="sp-status">Loading scratchpad…</p>}
          {phase === 'error' && (
            <p className="sp-status error">
              Failed to load the scratchpad.{' '}
              <button className="btn" onClick={load}>
                Retry
              </button>
            </p>
          )}
          {phase === 'ready' && (
            <>
              <div
                ref={padRef}
                className={`sp-pad${tool === 'eraser' ? ' eraser' : ''}`}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerEnd}
                onPointerCancel={onPointerEnd}
              >
                {Array.from({ length: tiles }, (_, i) => {
                  const logicalH = Math.min(TILE_HEIGHT, pageHeight - i * TILE_HEIGHT)
                  return (
                    <div
                      key={i}
                      className="sp-tile"
                      data-tile={i}
                      style={{ aspectRatio: `${PAD_WIDTH} / ${logicalH}` }}
                    >
                      {activeTiles.has(i) && <canvas ref={(el) => canvasRef(i, el)} />}
                    </div>
                  )
                })}
              </div>
              <button
                className="btn sp-extend"
                onClick={extendPage}
                disabled={pageHeight >= MAX_PAGE_HEIGHT}
              >
                Extend page
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
