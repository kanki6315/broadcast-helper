import { HashRouter, Routes, Route, useParams } from 'react-router-dom'
import './App.css'
import Layout from './components/Layout'
import InfoModalProvider from './components/InfoModalProvider'
import SeriesDirectoryPage from './pages/SeriesDirectoryPage'
import SeasonLayout from './pages/season/SeasonLayout'
import HubPage from './pages/season/HubPage'
import SchedulePage from './pages/season/SchedulePage'
import StandingsPage from './pages/season/StandingsPage'
import StatsPage from './pages/season/StatsPage'
import ResultsPage from './pages/season/ResultsPage'
import EntriesPage from './pages/season/EntriesPage'
import PhotosPage from './pages/season/PhotosPage'
import EventDetailPage from './pages/EventDetailPage'
import ImportsPage from './pages/ImportsPage'
import LogosPage from './pages/LogosPage'
import SeriesPage from './pages/SeriesPage'
import SheetPage from './pages/SheetPage'

// The sheet renders standalone (no app chrome) so the printed page is clean.
function SheetRoute() {
  const { eventId } = useParams()
  return <SheetPage eventId={Number(eventId)} />
}

export default function App() {
  return (
    <HashRouter>
      {/* Provider sits outside the routes so the info modals open from the
          app chrome (⌘K) and from the standalone sheet alike. */}
      <InfoModalProvider>
        <Routes>
          <Route path="/sheet/:eventId" element={<SheetRoute />} />
          <Route element={<Layout />}>
            <Route path="/" element={<SeriesDirectoryPage />} />
            <Route path="/seasons/:seasonId" element={<SeasonLayout />}>
              <Route index element={<HubPage />} />
              <Route path="schedule" element={<SchedulePage />} />
              <Route path="standings" element={<StandingsPage />} />
              <Route path="stats" element={<StatsPage />} />
              <Route path="results" element={<ResultsPage />} />
              <Route path="entries" element={<EntriesPage />} />
              <Route path="photos" element={<PhotosPage />} />
            </Route>
            <Route path="/events/:eventId" element={<EventDetailPage />} />
            <Route path="/imports" element={<ImportsPage />} />
            <Route path="/logos" element={<LogosPage />} />
            <Route path="/series" element={<SeriesPage />} />
          </Route>
        </Routes>
      </InfoModalProvider>
    </HashRouter>
  )
}
