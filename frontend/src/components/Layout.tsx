import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { useMe } from '../lib/auth'
import { getTheme, setTheme, type Theme } from '../lib/theme'
import SearchPalette, { SearchIcon, isMacLike } from './SearchPalette'
import InstallHint from './InstallHint'

const TABS = [
  { to: '/', label: 'Series', end: true },
  { to: '/manage', label: 'Manage', end: false, adminOnly: true },
]

const THEMES: { value: Theme; label: string; title: string }[] = [
  { value: 'system', label: 'Auto', title: 'Follow system theme' },
  { value: 'light', label: 'Light', title: 'Force light theme' },
  { value: 'dark', label: 'Dark', title: 'Force dark theme' },
]

function ThemeToggle() {
  const [theme, setThemeState] = useState<Theme>(() => getTheme())

  function pick(t: Theme) {
    setTheme(t)
    setThemeState(t)
  }

  return (
    <div className="theme-toggle" role="group" aria-label="Theme">
      {THEMES.map((t) => (
        <button
          key={t.value}
          type="button"
          title={t.title}
          className={theme === t.value ? 'active' : undefined}
          aria-pressed={theme === t.value}
          onClick={() => pick(t.value)}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

export default function Layout() {
  const me = useMe()
  const [searchOpen, setSearchOpen] = useState(false)
  const authError = new URLSearchParams(window.location.search).get('authError')

  // ⌘K / Ctrl-K opens driver search from anywhere in the app chrome, even
  // mid-typing — standard command-palette behavior.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  function login() {
    window.location.href = '/oauth2/authorization/google'
  }

  function logout() {
    void fetch('/logout', { method: 'POST' }).then(() => {
      window.location.href = '/'
    })
  }

  if (!me) return null // brief: waiting on /api/me

  // Auth on but not signed in → a minimal login screen; the button is the only
  // interactive control (no app data loads for a signed-out visitor anyway, since
  // every /api call but /api/me requires a session). A 401 mid-session routes the
  // user back here (see lib/authRedirect).
  if (me.authEnabled && !me.email) {
    return (
      <main className="login-screen">
        <div className="login-brand">
          <span className="login-brand-mark" aria-hidden="true" />
          <h1>Pit Pass</h1>
        </div>
        {authError ? (
          <p className="error">
            That Google account isn’t on the allowlist. Ask the owner to add your email, then try
            again.
          </p>
        ) : (
          <p className="muted">Sign in to continue.</p>
        )}
        <button type="button" className="login-button" onClick={login}>
          Sign in with Google
        </button>
      </main>
    )
  }

  return (
    <main className="container">
      <InstallHint />
      <div className="topbar">
        <Link to="/" className="wordmark">
          <span className="wordmark-mark" aria-hidden="true" />
          <span className="wordmark-name">Pit <strong>Pass</strong></span>
        </Link>
        <div className="topbar-side">
          <button
            type="button"
            className="search-trigger"
            aria-label="Search drivers"
            title={`Search drivers (${isMacLike ? '⌘K' : 'Ctrl K'})`}
            onClick={() => setSearchOpen(true)}
          >
            <SearchIcon />
            <span className="search-trigger-label">Search</span>
            <kbd className="sp-kbd">{isMacLike ? '⌘K' : 'Ctrl K'}</kbd>
          </button>
          <ThemeToggle />
          {me.email && (
            <div className="account">
              <span className="muted">{me.email}</span>
              <button type="button" onClick={logout}>
                Log out
              </button>
            </div>
          )}
        </div>
      </div>
      <nav className="tabs">
        {TABS.filter((t) => !t.adminOnly || me.isAdmin).map((t) => (
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
      <SearchPalette open={searchOpen} onClose={() => setSearchOpen(false)} />
    </main>
  )
}
