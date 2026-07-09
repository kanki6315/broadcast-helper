import { useState } from 'react'
import './App.css'
import SeriesPage from './pages/SeriesPage'
import ImportsPage from './pages/ImportsPage'
import EventsPage from './pages/EventsPage'
import StandingsPage from './pages/StandingsPage'
import ImagesPage from './pages/ImagesPage'

const TABS = ['Imports', 'Events', 'Standings', 'Images', 'Series'] as const
type Tab = (typeof TABS)[number]

function App() {
  const [tab, setTab] = useState<Tab>('Imports')

  return (
    <main className="container">
      <h1>Broadcast Helper</h1>
      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t} className={t === tab ? 'tab active' : 'tab'} onClick={() => setTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      {tab === 'Imports' && <ImportsPage />}
      {tab === 'Events' && <EventsPage />}
      {tab === 'Standings' && <StandingsPage />}
      {tab === 'Images' && <ImagesPage />}
      {tab === 'Series' && <SeriesPage />}
    </main>
  )
}

export default App
