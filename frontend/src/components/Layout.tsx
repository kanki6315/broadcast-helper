import { NavLink, Outlet } from 'react-router-dom'

const TABS = [
  { to: '/', label: 'Seasons', end: true },
  { to: '/imports', label: 'Imports', end: false },
  { to: '/logos', label: 'Logos', end: false },
  { to: '/series', label: 'Series', end: false },
]

export default function Layout() {
  return (
    <main className="container">
      <h1>Broadcast Helper</h1>
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
