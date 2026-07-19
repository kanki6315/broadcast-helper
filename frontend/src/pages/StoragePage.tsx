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

export default function StoragePage() {
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)

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

  return (
    <section className="storage-page">
      <h2>Offline storage</h2>
      <p>
        Cache diagnostics for the installable app. These numbers are specific to{' '}
        <strong>this device and browser</strong> — open this page in the installed app on the iPad to
        see its real quota and cached data.
      </p>
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
