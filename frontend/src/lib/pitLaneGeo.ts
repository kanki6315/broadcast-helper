// Pit-lane geometry from GPS anchors. Anchors are fixes captured standing at
// the center of a numbered box; boxes between two anchors interpolate
// linearly on box number, so the lane is a piecewise polyline through the
// anchors. All math is planar: an equirectangular projection around the
// anchor centroid is millimetre-honest at pit-lane scale (<1 km).
//
// Pure functions, no React and no geolocation access — the modal feeds fixes
// in, which also makes the whole module drivable from the console.

export interface GeoAnchor {
  boxNumber: number
  lat: number
  lng: number
}

export interface GeoFix {
  lat: number
  lng: number
}

export interface Guidance {
  /** Fractional box the fix projects to ("you're near box 12.4"). */
  currentBox: number
  /** Walking distance along the lane to the target box, in feet. */
  feet: number
  /** Numeric box-count gap (what the wall numbers say), not distance/25'. */
  boxesAway: number
  /** Which way to walk; box numbers increase toward pit in. Null on arrival. */
  direction: 'pit-in' | 'pit-out' | null
  /** Within about one box of the target. */
  arrived: boolean
}

const M_PER_DEG_LAT = 111320
const BOX_METERS = 7.62 // 25 feet
const FEET_PER_METER = 3.28084

interface Pt {
  x: number
  y: number
}

interface Lane {
  boxes: number[] // anchor box numbers, ascending
  points: Pt[] // projected anchor positions
  arc: number[] // cumulative along-lane metres at each anchor
}

function project(anchors: GeoAnchor[], fix: GeoFix): { lane: Lane; here: Pt } | null {
  if (anchors.length < 2) return null
  const sorted = [...anchors].sort((a, b) => a.boxNumber - b.boxNumber)
  const lat0 = sorted.reduce((sum, a) => sum + a.lat, 0) / sorted.length
  const lng0 = sorted.reduce((sum, a) => sum + a.lng, 0) / sorted.length
  const mPerDegLng = M_PER_DEG_LAT * Math.cos((lat0 * Math.PI) / 180)
  const toPt = (p: GeoFix): Pt => ({ x: (p.lng - lng0) * mPerDegLng, y: (p.lat - lat0) * M_PER_DEG_LAT })

  const points = sorted.map(toPt)
  const arc = [0]
  for (let i = 1; i < points.length; i++) {
    arc.push(arc[i - 1] + Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y))
  }
  // Two anchors on the same spot describe no line — refuse rather than NaN.
  if (arc[arc.length - 1] < 1) return null
  return { lane: { boxes: sorted.map((a) => a.boxNumber), points, arc }, here: toPt(fix) }
}

/** Along-lane metres for a (possibly fractional) box number; linear per
 *  segment, extrapolated along the end segments beyond the outermost anchors. */
function arcAtBox(lane: Lane, box: number): number {
  const { boxes, arc } = lane
  const last = boxes.length - 1
  const i = box <= boxes[0] ? 0 : box >= boxes[last] ? last - 1 : boxes.findIndex((_, j) => boxes[j + 1] >= box)
  const t = (box - boxes[i]) / (boxes[i + 1] - boxes[i])
  return arc[i] + t * (arc[i + 1] - arc[i])
}

/** Nearest point on the polyline → fractional box + along-lane metres. The
 *  end segments extend past their anchors so standing before box 1 (or past
 *  the last anchor) still yields a sensible estimate. */
function locate(lane: Lane, here: Pt): { box: number; arc: number } {
  const { boxes, points, arc } = lane
  let best = { box: boxes[0], arc: 0, dist: Infinity }
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i]
    const b = points[i + 1]
    const dx = b.x - a.x
    const dy = b.y - a.y
    const lenSq = dx * dx + dy * dy
    let t = lenSq === 0 ? 0 : ((here.x - a.x) * dx + (here.y - a.y) * dy) / lenSq
    // Interior joints clamp; the outermost segments extrapolate.
    const lo = i === 0 ? -Infinity : 0
    const hi = i === points.length - 2 ? Infinity : 1
    t = Math.min(hi, Math.max(lo, t))
    const px = a.x + t * dx
    const py = a.y + t * dy
    const dist = Math.hypot(here.x - px, here.y - py)
    if (dist < best.dist) {
      best = {
        box: boxes[i] + t * (boxes[i + 1] - boxes[i]),
        arc: arc[i] + t * (arc[i + 1] - arc[i]),
        dist,
      }
    }
  }
  return best
}

/** Null when fewer than two distinct anchors exist. */
export function guide(anchors: GeoAnchor[], fix: GeoFix, targetBox: number): Guidance | null {
  const projected = project(anchors, fix)
  if (!projected) return null
  const at = locate(projected.lane, projected.here)
  const meters = Math.abs(arcAtBox(projected.lane, targetBox) - at.arc)
  const arrived = meters <= BOX_METERS
  return {
    currentBox: at.box,
    feet: meters * FEET_PER_METER,
    boxesAway: Math.abs(targetBox - Math.round(at.box)),
    direction: arrived ? null : targetBox > at.box ? 'pit-in' : 'pit-out',
    arrived,
  }
}

/** "~8 boxes (200 ft)" — boxes say what the wall numbers will, feet say how
 *  far the walk is; the two deliberately disagree across the lane's physical
 *  breaks. */
export function guidanceText(g: Guidance): string {
  const feet = Math.max(10, Math.round(g.feet / 10) * 10)
  const boxes = g.boxesAway === 1 ? '1 box' : `${g.boxesAway} boxes`
  return `~${boxes} (${feet} ft)`
}

export interface FixSample {
  lat: number
  lng: number
  /** Reported 1-sigma-ish radius in metres; smaller = trusted more. */
  accuracy: number
}

export interface AveragedFix {
  lat: number
  lng: number
  /** Honest quality figure: the worse of the mean reported accuracy and the
   *  actual scatter of the samples. A multipath-shifted capture can report
   *  ±4 m while jumping 10 m between fixes — the scatter exposes it. */
  accuracyM: number
  /** Samples that survived outlier rejection. */
  used: number
}

/**
 * Inverse-variance weighted average of a sampling window's fixes. Samples
 * reporting worse than 2.5x the window's best accuracy (min 25 m) are
 * dropped as outliers unless that would leave fewer than three. Averaging
 * cancels fix-to-fix jitter; it cannot cancel a bias shared by the whole
 * window, which is why accuracyM folds the observed scatter in.
 */
export function averageFixes(samples: FixSample[]): AveragedFix | null {
  if (samples.length === 0) return null
  const best = Math.min(...samples.map((s) => s.accuracy))
  const cutoff = Math.max(25, best * 2.5)
  const kept0 = samples.filter((s) => s.accuracy <= cutoff)
  const kept = kept0.length >= 3 || kept0.length === samples.length ? kept0 : samples

  let wSum = 0
  let lat = 0
  let lng = 0
  let accSum = 0
  for (const s of kept) {
    const w = 1 / Math.max(1, s.accuracy * s.accuracy)
    wSum += w
    lat += s.lat * w
    lng += s.lng * w
    accSum += s.accuracy * w
  }
  lat /= wSum
  lng /= wSum

  const mPerDegLng = M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180)
  let scatterSq = 0
  for (const s of kept) {
    const dx = (s.lng - lng) * mPerDegLng
    const dy = (s.lat - lat) * M_PER_DEG_LAT
    scatterSq += dx * dx + dy * dy
  }
  const rms = Math.sqrt(scatterSq / kept.length)
  return { lat, lng, accuracyM: Math.max(accSum / wSum, rms), used: kept.length }
}
