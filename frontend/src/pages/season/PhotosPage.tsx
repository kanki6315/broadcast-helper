import SeasonImages from '../../components/SeasonImages'
import { useSeason } from './SeasonLayout'

/** Season car-photo management (bulk upload, number matching) — carried over
 * from the old hub as its own sub-page. */
export default function PhotosPage() {
  const { hub } = useSeason()
  return <SeasonImages seasonId={hub.id} />
}
