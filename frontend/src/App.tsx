import { HashRouter, Navigate, Routes, Route, useParams } from 'react-router-dom'
import './App.css'
import Layout from './components/Layout'
import ManageLayout from './components/ManageLayout'
import InfoModalProvider from './components/InfoModalProvider'
import { AuthProvider } from './lib/auth'
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
import TeamsPage from './pages/TeamsPage'
import UsersPage from './pages/UsersPage'
import SessionsPage from './pages/SessionsPage'
import StoragePage from './pages/StoragePage'
import UpdatePrompt from './components/UpdatePrompt'
import DataNudge from './components/DataNudge'

// The sheet renders standalone (no app chrome) so the printed page is clean.
function SheetRoute() {
  const { eventId } = useParams()
  return <SheetPage eventId={Number(eventId)} />
}

export default function App() {
  return (
    <HashRouter>
      {/* Providers sit outside the routes so the info modals open from the
          app chrome (⌘K) and from the standalone sheet alike — and so both
          see the same auth state for gating edit controls. */}
      <AuthProvider>
        <InfoModalProvider>
          <UpdatePrompt />
          <DataNudge />
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
              <Route path="/manage" element={<ManageLayout />}>
                <Route index element={<Navigate to="series" replace />} />
                <Route path="series" element={<SeriesPage />} />
                <Route path="teams" element={<TeamsPage />} />
                <Route path="imports" element={<ImportsPage />} />
                <Route path="logos" element={<LogosPage />} />
                <Route path="users" element={<UsersPage />} />
                <Route path="sessions" element={<SessionsPage />} />
                <Route path="storage" element={<StoragePage />} />
              </Route>
              <Route path="/imports" element={<Navigate to="/manage/imports" replace />} />
              <Route path="/logos" element={<Navigate to="/manage/logos" replace />} />
              <Route path="/series" element={<Navigate to="/manage/series" replace />} />
            </Route>
          </Routes>
        </InfoModalProvider>
      </AuthProvider>
    </HashRouter>
  )
}
