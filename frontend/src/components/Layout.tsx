import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'

const TABS = [
  { to: '/', label: 'Seasons', end: true },
  { to: '/imports', label: 'Imports', end: false },
  { to: '/logos', label: 'Logos', end: false },
  { to: '/series', label: 'Series', end: false },
]

interface Me {
  authEnabled: boolean
  email: string | null
}

export default function Layout() {
  const [me, setMe] = useState<Me | null>(null)
  const authError = new URLSearchParams(window.location.search).get('authError')

  useEffect(() => {
    void fetch('/api/me')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((m: Me) => {
        // Auth on but not signed in → hand off to Google; the SPA reloads
        // authenticated afterwards. (A rejected email comes back as authError.)
        if (m.authEnabled && !m.email && !authError) {
          window.location.href = '/oauth2/authorization/google'
          return
        }
        setMe(m)
      })
      .catch(() => setMe({ authEnabled: false, email: null }))
  }, [authError])

  function logout() {
    void fetch('/logout', { method: 'POST' }).then(() => {
      window.location.href = '/'
    })
  }

  if (!me) return null // brief: waiting on /api/me (or redirecting to Google)

  if (authError && !me.email) {
    return (
      <main className="container">
        <h1>Broadcast Helper</h1>
        <p className="error">
          That Google account isn’t on the allowlist. Ask the owner to add your email, then{' '}
          <a href="/oauth2/authorization/google">try again</a>.
        </p>
      </main>
    )
  }

  return (
    <main className="container">
      <div className="topbar">
        <h1>Broadcast Helper</h1>
        {me.email && (
          <div className="account">
            <span className="muted">{me.email}</span>
            <button type="button" onClick={logout}>
              Log out
            </button>
          </div>
        )}
      </div>
      <nav className="tabs">
        {TABS.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            end={t.end}
            className={({ isActive }) => (isActive ? 'tab active' : 'tab')}
          >
            {t.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </main>
  )
}
