import { useEffect, useState } from 'react'

interface SessionRow {
  id: string
  email: string
  createdAt: string
  lastActiveAt: string
  current: boolean
}

/** A linked iPad (native-app bearer token). Never carries the token itself. */
interface DeviceRow {
  id: number
  email: string
  deviceName: string
  createdAt: string
  lastUsedAt: string | null
}

/**
 * Active sign-in sessions, plus the iPads linked through the native app (each
 * holds a long-lived device token instead of a session). Revoking either forces
 * that person to sign in again — it does NOT remove their access (that's the
 * Users page). Sessions are addressed by an internal id, never the session
 * cookie; devices by their token row id, never the token.
 */
export default function SessionsPage() {
  const [rows, setRows] = useState<SessionRow[]>([])
  const [devices, setDevices] = useState<DeviceRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function load() {
    const [sessions, linked] = await Promise.all([
      fetch('/api/users/sessions'),
      fetch('/api/users/devices'),
    ])
    if (sessions.ok) setRows(await sessions.json())
    if (linked.ok) setDevices(await linked.json())
  }

  useEffect(() => {
    void load()
  }, [])

  async function call(input: string, init: RequestInit): Promise<void> {
    setBusy(true)
    setError(null)
    const res = await fetch(input, init)
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Request failed (${res.status})`)
    }
    // Revoking your own session 401s the next fetch, which authRedirect turns
    // into a bounce to login — the intended outcome.
    await load()
    setBusy(false)
  }

  function revoke(row: SessionRow) {
    if (row.current && !window.confirm('This signs you out on this device — continue?')) return
    void call(`/api/users/sessions/${row.id}`, { method: 'DELETE' })
  }

  function signOutEverywhere(email: string) {
    void call(`/api/users/sessions?email=${encodeURIComponent(email)}`, { method: 'DELETE' })
  }

  function unlinkDevice(row: DeviceRow) {
    if (!window.confirm(`Unlink "${row.deviceName}"? The app on it will ask to sign in again.`)) return
    void call(`/api/users/devices/${row.id}`, { method: 'DELETE' })
  }

  // "Sign out everywhere" belongs once per email; show it on the first row of any
  // email that has more than one session.
  const countByEmail = rows.reduce<Record<string, number>>((acc, r) => {
    acc[r.email] = (acc[r.email] ?? 0) + 1
    return acc
  }, {})
  const firstSeen = new Set<string>()

  return (
    <section className="sessions-page">
      <h2>Sessions</h2>
      <p>
        Everyone currently signed in. <strong>Revoke</strong> forces that person to sign in again;
        it doesn&apos;t remove their access — to do that, remove them on the Users page.
      </p>
      {error && <p className="error">{error}</p>}

      {rows.length === 0 ? (
        <p className="muted">No active sessions.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Email</th>
              <th>Signed in</th>
              <th>Last active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const showAll = countByEmail[r.email] > 1 && !firstSeen.has(r.email)
              firstSeen.add(r.email)
              return (
                <tr key={r.id}>
                  <td>
                    {r.email}
                    {r.current && <span className="muted"> (this device)</span>}
                  </td>
                  <td>{new Date(r.createdAt).toLocaleString()}</td>
                  <td>{new Date(r.lastActiveAt).toLocaleString()}</td>
                  <td>
                    <button type="button" disabled={busy} onClick={() => revoke(r)}>
                      Revoke
                    </button>{' '}
                    {showAll && (
                      <button type="button" disabled={busy} onClick={() => signOutEverywhere(r.email)}>
                        Sign out everywhere
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      <h2>Linked devices</h2>
      <p>
        iPads signed in through the Pit Pass app. Each holds its own long-lived sign-in;{' '}
        <strong>Unlink</strong> signs that device out. Access is still decided by the Users page.
      </p>
      {devices.length === 0 ? (
        <p className="muted">No linked devices.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Email</th>
              <th>Device</th>
              <th>Linked</th>
              <th>Last used</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.email}</td>
                <td>{d.deviceName}</td>
                <td>{new Date(d.createdAt).toLocaleString()}</td>
                <td>{d.lastUsedAt ? new Date(d.lastUsedAt).toLocaleString() : <span className="muted">never</span>}</td>
                <td>
                  <button type="button" disabled={busy} onClick={() => unlinkDevice(d)}>
                    Unlink
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
