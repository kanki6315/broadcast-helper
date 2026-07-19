import { useEffect, useState } from 'react'
import { useMe } from '../lib/auth'

interface UserRow {
  id: number
  email: string
  role: 'ADMIN' | 'VIEWER'
  createdAt: string
}

interface DeniedRow {
  id: number
  email: string
  attemptCount: number
  lastAttemptAt: string
}

/**
 * The access roster. This table is the only access list — there are no env-var
 * fallbacks — so the backend refuses self-removal and removing the last admin;
 * those errors surface here verbatim.
 */
export default function UsersPage() {
  const me = useMe()
  const myEmail = me?.email?.toLowerCase() ?? null
  const [rows, setRows] = useState<UserRow[]>([])
  const [denied, setDenied] = useState<DeniedRow[]>([])
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<'ADMIN' | 'VIEWER'>('VIEWER')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function load() {
    const [usersRes, deniedRes] = await Promise.all([
      fetch('/api/users'),
      fetch('/api/users/denied'),
    ])
    if (usersRes.ok) setRows(await usersRes.json())
    setDenied(deniedRes.ok ? await deniedRes.json() : [])
  }

  useEffect(() => {
    void load()
  }, [])

  async function call(input: string, init: RequestInit): Promise<boolean> {
    setBusy(true)
    setError(null)
    const res = await fetch(input, init)
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      setError(body?.message ?? `Request failed (${res.status})`)
    }
    await load()
    setBusy(false)
    return res.ok
  }

  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim()) return
    const ok = await call('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.trim(), role }),
    })
    if (ok) {
      setEmail('')
      setRole('VIEWER')
    }
  }

  function setUserRole(id: number, newRole: string) {
    void call(`/api/users/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: newRole }),
    })
  }

  function remove(id: number) {
    void call(`/api/users/${id}`, { method: 'DELETE' })
  }

  return (
    <section className="users-page">
      <h2>Users</h2>
      <p>
        Everyone listed here can sign in and browse; <strong>admins</strong> can also import,
        manage series and edit notes. This list is the only way in — an account that isn&apos;t on
        it can&apos;t sign in at all.
      </p>
      {error && <p className="error">{error}</p>}

      <form className="users-form" onSubmit={(e) => void add(e)}>
        <input
          type="email"
          value={email}
          placeholder="teammate@gmail.com"
          aria-label="Email to add"
          disabled={busy}
          onChange={(e) => setEmail(e.target.value)}
        />
        <select
          value={role}
          aria-label="Role for new user"
          disabled={busy}
          onChange={(e) => setRole(e.target.value as 'ADMIN' | 'VIEWER')}
        >
          <option value="VIEWER">Viewer</option>
          <option value="ADMIN">Admin</option>
        </select>
        <button type="submit" className="btn btn-primary" disabled={busy || !email.trim()}>
          Add user
        </button>
      </form>

      <h3>Users ({rows.length})</h3>
      <table>
        <thead>
          <tr>
            <th>Email</th>
            <th>Role</th>
            <th>Added</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const self = myEmail != null && r.email.toLowerCase() === myEmail
            return (
              <tr key={r.id}>
                <td>
                  {r.email}
                  {self && <span className="muted"> (you)</span>}
                </td>
                <td>
                  {self ? (
                    r.role === 'ADMIN' ? 'Admin' : 'Viewer'
                  ) : (
                    <select
                      value={r.role}
                      aria-label={`Role for ${r.email}`}
                      disabled={busy}
                      onChange={(e) => setUserRole(r.id, e.target.value)}
                    >
                      <option value="VIEWER">Viewer</option>
                      <option value="ADMIN">Admin</option>
                    </select>
                  )}
                </td>
                <td>{new Date(r.createdAt).toLocaleDateString()}</td>
                <td>
                  {!self && (
                    <button type="button" disabled={busy} onClick={() => remove(r.id)}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {denied.length > 0 && (
        <>
          <h3>Denied sign-ins ({denied.length})</h3>
          <p className="muted">
            These Google accounts tried to sign in but aren&apos;t on the list — usually a teammate
            using a different account than expected. Add them as a viewer (promote above if
            needed) or dismiss.
          </p>
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Attempts</th>
                <th>Last attempt</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {denied.map((d) => (
                <tr key={d.id}>
                  <td>{d.email}</td>
                  <td>{d.attemptCount}</td>
                  <td>{new Date(d.lastAttemptAt).toLocaleString()}</td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-primary"
                      disabled={busy}
                      onClick={() =>
                        void call('/api/users', {
                          method: 'POST',
                          headers: { 'Content-Type': 'application/json' },
                          body: JSON.stringify({ email: d.email, role: 'VIEWER' }),
                        })
                      }
                    >
                      Add as viewer
                    </button>{' '}
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void call(`/api/users/denied/${d.id}`, { method: 'DELETE' })}
                    >
                      Dismiss
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}
