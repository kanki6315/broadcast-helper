// Pure geometry and document helpers for the drawing scratchpad. The pad's
// coordinate space is a fixed 800-wide logical column (y grows downward to
// pageHeight); strokes store flat [x0,y0,x1,y1,...] pairs rounded to a tenth
// of a logical px — whole-px rounding staircased slow Pencil handwriting —
// which halves the JSON overhead of nested pairs and keeps tokens short.

export interface Stroke {
  /** Client-generated, unique per stroke. The backend stores it as opaque
   *  JSONB; it exists so two divergent copies of a pad can one day be merged
   *  as a set union instead of a whole-document choice. Optional because
   *  pre-offline-writes pads persisted strokes without it. */
  id?: string
  /** Only 'pen' exists today; the field future-proofs highlighters etc.
   *  without a storage migration. */
  tool: 'pen'
  color: string
  size: number
  points: number[]
}

/** Compact unique stroke id (~11 chars beats a 36-char UUID against the
 *  pad's 2 MB serialized cap). */
export function newStrokeId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

export type PadAction =
  | { type: 'add'; stroke: Stroke }
  /** One eraser drag. Removals are recorded in the order they happened, each
   *  with the index it was spliced from at that moment — undoing in reverse
   *  order replays the exact inverse, so z-order survives. */
  | { type: 'erase'; removed: { index: number; stroke: Stroke }[] }

export const PAD_WIDTH = 800
/** Logical height of one canvas tile. iPad Safari caps canvas backing-store
 *  size, so the pad is a stack of tiles rather than one tall canvas. */
export const TILE_HEIGHT = 1024
/** Points closer than this (logical px) to the last kept point are dropped —
 *  a 30–60% payload cut with no visual difference at pen widths ≥ 2. */
export const MIN_POINT_GAP = 1.5

export interface BBox {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

/**
 * Append a stroke's path to the current one: quadratic curves through
 * consecutive segment midpoints with the samples as control points — the
 * standard smooth-ink construction, so polyline chords never show as facets.
 * The pen starts exactly at the first point and finishes exactly at the last
 * (a straight half-chord tail); a single pair draws a round-cap dot. Caller
 * owns beginPath/stroke and styling.
 */
export function traceStroke(ctx: CanvasRenderingContext2D, p: number[]): void {
  ctx.moveTo(p[0], p[1])
  if (p.length <= 4) {
    ctx.lineTo(p[p.length - 2], p[p.length - 1])
    return
  }
  for (let i = 2; i <= p.length - 4; i += 2) {
    ctx.quadraticCurveTo(p[i], p[i + 1], (p[i] + p[i + 2]) / 2, (p[i + 1] + p[i + 3]) / 2)
  }
  ctx.lineTo(p[p.length - 2], p[p.length - 1])
}

/** Stroke bounds inflated by half the pen width (plus a hair of antialias). */
export function strokeBBox(s: Stroke): BBox {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (let i = 0; i < s.points.length; i += 2) {
    const x = s.points[i]
    const y = s.points[i + 1]
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }
  const pad = s.size / 2 + 1
  return { minX: minX - pad, minY: minY - pad, maxX: maxX + pad, maxY: maxY + pad }
}

export function tileCount(pageHeight: number): number {
  return Math.max(1, Math.ceil(pageHeight / TILE_HEIGHT))
}

export function strokeIntersectsTile(b: BBox, tile: number): boolean {
  return b.maxY >= tile * TILE_HEIGHT && b.minY <= (tile + 1) * TILE_HEIGHT
}

/** Tiles a bbox spans, clamped to the page — the redraw set after an erase
 *  or undo touches only these. */
export function tilesForBBox(b: BBox, pageHeight: number): number[] {
  const last = Math.min(tileCount(pageHeight) - 1, Math.floor(b.maxY / TILE_HEIGHT))
  const first = Math.max(0, Math.floor(b.minY / TILE_HEIGHT))
  const tiles: number[] = []
  for (let t = first; t <= last; t++) tiles.push(t)
  return tiles
}

/** Append a point unless it's within MIN_POINT_GAP of the last kept one.
 *  Returns whether the point was kept. */
export function thinAppend(points: number[], x: number, y: number): boolean {
  const n = points.length
  if (n >= 2) {
    const dx = x - points[n - 2]
    const dy = y - points[n - 1]
    if (dx * dx + dy * dy < MIN_POINT_GAP * MIN_POINT_GAP) return false
  }
  points.push(x, y)
  return true
}

function segmentDistanceSq(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const abx = bx - ax
  const aby = by - ay
  const lenSq = abx * abx + aby * aby
  let t = lenSq === 0 ? 0 : ((px - ax) * abx + (py - ay) * aby) / lenSq
  t = Math.max(0, Math.min(1, t))
  const dx = px - (ax + t * abx)
  const dy = py - (ay + t * aby)
  return dx * dx + dy * dy
}

/** Indices (ascending) of strokes the eraser touches at (x, y): bbox
 *  prefilter, then true distance to each polyline segment against the
 *  eraser radius plus half the stroke's own width. */
export function eraserHits(strokes: Stroke[], x: number, y: number, radius: number): number[] {
  const hits: number[] = []
  for (let i = 0; i < strokes.length; i++) {
    const s = strokes[i]
    const b = strokeBBox(s)
    if (x < b.minX - radius || x > b.maxX + radius || y < b.minY - radius || y > b.maxY + radius) continue
    const reach = radius + s.size / 2
    const reachSq = reach * reach
    const p = s.points
    if (p.length === 2) {
      if (segmentDistanceSq(x, y, p[0], p[1], p[0], p[1]) <= reachSq) hits.push(i)
      continue
    }
    for (let j = 0; j + 3 < p.length; j += 2) {
      if (segmentDistanceSq(x, y, p[j], p[j + 1], p[j + 2], p[j + 3]) <= reachSq) {
        hits.push(i)
        break
      }
    }
  }
  return hits
}

/** Mutates `strokes` to reverse `action`. */
export function applyUndo(strokes: Stroke[], action: PadAction): void {
  if (action.type === 'add') {
    const i = strokes.indexOf(action.stroke)
    if (i >= 0) strokes.splice(i, 1)
  } else {
    for (let i = action.removed.length - 1; i >= 0; i--) {
      const r = action.removed[i]
      strokes.splice(Math.min(r.index, strokes.length), 0, r.stroke)
    }
  }
}

/** Mutates `strokes` to re-apply an undone `action`. */
export function applyRedo(strokes: Stroke[], action: PadAction): void {
  if (action.type === 'add') {
    strokes.push(action.stroke)
  } else {
    for (const r of action.removed) {
      const i = strokes.indexOf(r.stroke)
      if (i >= 0) strokes.splice(i, 1)
    }
  }
}

/** Strokes an action touches — the tiles to redraw after undo/redo. */
export function actionStrokes(action: PadAction): Stroke[] {
  return action.type === 'add' ? [action.stroke] : action.removed.map((r) => r.stroke)
}
