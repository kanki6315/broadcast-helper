import { useCallback, useEffect, useState } from 'react'

// On-device diagnostics for the installable PWA's offline cache. The real
// numbers are device- and browser-specific (only the device knows its quota),
// so this is most useful opened in the installed app on the iPad itself.

interface Report {
  quota: number | null
  usage: number | null
  persisted: boolean | null
  caches: { name: string; entries: number }[]
}

function fmtBytes(n: number | null): string {
  if (n == null) return '—'
  if (n < 1024) return `${n} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v < 10 ? 2 : 1)} ${units[i]}`
}

async function collect(): Promise<Report> {
  const est = navigator.storage?.estimate ? await navigator.storage.estimate() : {}
  const persisted = navigator.storage?.persisted ? await navigator.storage.persisted() : null
  const names = 'caches' in self ? await caches.keys() : []
  const perCache = await Promise.all(
    names.map(async (name) => ({ name, entries: (await (await caches.open(name)).keys()).length })),
  )
  return {
    quota: est.quota ?? null,
    usage: est.usage ?? null,
    persisted,
    caches: perCache.sort((a, b) => a.name.localeCompare(b.name)),
  }
}

interface Viewport {
  /** What CSS lays out against; on iPad standalone this can stay stuck at the
   * full-screen width and not track a resized Stage Manager window. */
  layoutW: number
  layoutH: number
  /** Tracks the actual visible window (via the visualViewport API). */
  visualW: number
  visualH: number
  innerW: number
  innerH: number
  screenW: number
  screenH: number
  dpr: number
  scale: number
}

function readViewport(): Viewport {
  const vv = window.visualViewport
  return {
    layoutW: document.documentElement.clientWidth,
    layoutH: document.documentElement.clientHeight,
    visualW: vv ? Math.round(vv.width) : window.innerWidth,
    visualH: vv ? Math.round(vv.height) : window.innerHeight,
    innerW: window.innerWidth,
    innerH: window.innerHeight,
    screenW: window.screen.width,
    screenH: window.screen.height,
    dpr: window.devicePixelRatio,
    scale: vv ? Number(vv.scale.toFixed(3)) : 1,
  }
}

export default function StoragePage() {
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)
  const [vp, setVp] = useState<Viewport>(readViewport)

  // Live-track viewport dimensions so resizing the window (e.g. iPad Stage
  // Manager) updates the readout — the point is to watch which dimension tracks
  // the window and which stays stuck.
  useEffect(() => {
    const onResize = () => setVp(readViewport())
    window.addEventListener('resize', onResize)
    window.visualViewport?.addEventListener('resize', onResize)
    window.visualViewport?.addEventListener('scroll', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      window.visualViewport?.removeEventListener('resize', onResize)
      window.visualViewport?.removeEventListener('scroll', onResize)
    }
  }, [])

  const refresh = useCallback(() => {
    setBusy(true)
    void collect()
      .then(setReport)
      .finally(() => setBusy(false))
  }, [])

  useEffect(() => refresh(), [refresh])

  const secure = window.isSecureContext
  const pct =
    report?.quota && report.usage != null
      ? ((report.usage / report.quota) * 100).toFixed(report.usage / report.quota < 0.01 ? 3 : 1)
      : null

  async function requestPersist() {
    if (!navigator.storage?.persist) return
    await navigator.storage.persist()
    refresh()
  }

  // Clears the runtime API caches only (never the precached app shell), forcing
  // the next online load to refetch — handy when validating cache behaviour.
  async function clearApiCaches() {
    if (!('caches' in self)) return
    setBusy(true)
    const names = await caches.keys()
    await Promise.all(
      names.filter((n) => n.startsWith('api-') || n === 'pdfjs').map((n) => caches.delete(n)),
    )
    refresh()
  }

  const widthGap = vp.layoutW - vp.visualW
  const heightGap = vp.layoutH - vp.visualH

  return (
    <section className="storage-page">
      <h2>Diagnostics</h2>
      <p>
        On-device diagnostics for the installable app. These numbers are specific to{' '}
        <strong>this device and browser</strong> — open this page in the installed app on the iPad to
        see real values.
      </p>

      <h3>Viewport</h3>
      <p>
        If <strong>layout ≠ window</strong>, CSS is laying out against a size other than the visible
        window — the cause of content overflowing a resized Stage Manager window. Resize the window
        and watch which row tracks it.
      </p>
      <table>
        <tbody>
          <tr>
            <th scope="row">Window (visual viewport)</th>
            <td>
              {vp.visualW} × {vp.visualH}
            </td>
          </tr>
          <tr>
            <th scope="row">Layout viewport</th>
            <td>
              {vp.layoutW} × {vp.layoutH}
            </td>
          </tr>
          <tr>
            <th scope="row">Layout − window</th>
            <td className={widthGap !== 0 || heightGap !== 0 ? 'vp-gap' : undefined}>
              {widthGap} × {heightGap} px{widthGap !== 0 && ' — layout is wider than the window'}
            </td>
          </tr>
          <tr>
            <th scope="row">window.inner</th>
            <td>
              {vp.innerW} × {vp.innerH}
            </td>
          </tr>
          <tr>
            <th scope="row">screen</th>
            <td>
              {vp.screenW} × {vp.screenH}
            </td>
          </tr>
          <tr>
            <th scope="row">devicePixelRatio / scale</th>
            <td>
              {vp.dpr} / {vp.scale}
            </td>
          </tr>
        </tbody>
      </table>

      <h3>Offline storage</h3>
      <p>Cache quota and per-bucket contents for the service worker.</p>
      {!secure && (
        <p className="error">
          Storage APIs and the service worker are unavailable in this insecure context, so the values
          below read blank. Load the app over HTTPS (the deployed site, or a tunnel) to see real
          numbers.
        </p>
      )}

      <table>
        <tbody>
          <tr>
            <th scope="row">Quota</th>
            <td>{fmtBytes(report?.quota ?? null)}</td>
          </tr>
          <tr>
            <th scope="row">Used</th>
            <td>
              {fmtBytes(report?.usage ?? null)}
              {pct != null && <span className="muted"> ({pct}%)</span>}
            </td>
          </tr>
          <tr>
            <th scope="row">Persistent storage</th>
            <td>{report?.persisted == null ? '—' : report.persisted ? 'yes' : 'no'}</td>
          </tr>
          <tr>
            <th scope="row">Secure context</th>
            <td>{secure ? 'yes' : 'no — needs HTTPS'}</td>
          </tr>
        </tbody>
      </table>

      <h3>Caches</h3>
      {report && report.caches.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Cache</th>
              <th>Entries</th>
            </tr>
          </thead>
          <tbody>
            {report.caches.map((c) => (
              <tr key={c.name}>
                <td>{c.name}</td>
                <td>{c.entries}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">No caches yet (nothing has been stored on this device).</p>
      )}

      <div className="storage-actions">
        <button type="button" onClick={refresh} disabled={busy}>
          {busy ? 'Reading…' : 'Refresh'}
        </button>
        <button
          type="button"
          onClick={requestPersist}
          disabled={busy || report?.persisted === true || !navigator.storage?.persist}
        >
          Request persistent storage
        </button>
        <button type="button" onClick={clearApiCaches} disabled={busy || !('caches' in self)}>
          Clear API caches
        </button>
      </div>
    </section>
  )
}
