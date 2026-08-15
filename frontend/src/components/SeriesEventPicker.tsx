import { useMemo } from 'react'
import './series-event-picker.css'
import Combobox, { type ComboOption } from './Combobox'
import { useSeriesEvents, type EventOption, type Series } from '../lib/useSeriesEvents'
import { formatEventDate } from '../lib/importGroups'

/**
 * A series + event typeahead pair, shared by the file-upload and iRacing import
 * modals. Pins one series and (optionally) one event so the staged batches land
 * pre-targeted in the review table. The event list is filtered to the chosen
 * series; a leading "auto" row leaves each import to place its own event.
 *
 * Controlled: the host holds seriesId/eventId and receives the resolved objects
 * through the change callbacks (so it can display the pin and seed the review
 * without its own lookup). The series/events fetch lives in useSeriesEvents;
 * the combobox itself is the shared Combobox (inline results — a host dialog
 * must never clip them).
 */

export default function SeriesEventPicker({
  seriesId,
  eventId,
  onSeriesChange,
  onEventChange,
  onError,
  required,
  autoLabel = 'Each import places itself',
  idPrefix = 'sep',
}: {
  seriesId: number | null
  eventId: number | null
  /** The chosen series (or null when cleared) with its resolved object. Emitting
   *  a series always resets the event — the host need not do so itself. */
  onSeriesChange: (seriesId: number | null, series: Series | null) => void
  onEventChange: (eventId: number | null, event: EventOption | null) => void
  onError?: (msg: string | null) => void
  /** Marks the series field with a required asterisk (host still gates submit). */
  required?: boolean
  /** Label for the "no pin" event row — e.g. "Each file places itself". */
  autoLabel?: string
  /** Namespaces the input ids so two pickers can't collide on one page. */
  idPrefix?: string
}) {
  const { allSeries, allEvents, addSeries } = useSeriesEvents((m) => onError?.(m))

  const series = allSeries?.find((s) => s.id === seriesId) ?? null

  const seriesOptions: ComboOption[] = useMemo(
    () =>
      (allSeries ?? []).map((s) => ({
        key: String(s.id),
        label: s.name,
        hint: s.abbreviation ?? undefined,
      })),
    [allSeries],
  )

  // Events pinned to the chosen series (matched on the always-live series name),
  // oldest first; a leading "auto" row is the default (no pin).
  const eventOptions: ComboOption[] = useMemo(() => {
    if (!series) return []
    const inSeries = (allEvents ?? [])
      .filter((e) => e.seriesName === series.name)
      .sort((a, b) => (a.eventDate ?? '').localeCompare(b.eventDate ?? ''))
    return [
      { key: 'auto', label: autoLabel, variant: 'auto' as const },
      ...inSeries.map((e) => ({
        key: String(e.id),
        label: e.name,
        hint: [
          e.circuitName,
          formatEventDate(e.eventDate, e.year),
          e.seasonKind === 'QUALIFIER' ? `Qualifying — ${e.seasonLabel ?? 'unnamed stage'}` : null,
        ].filter(Boolean).join(' · '),
      })),
    ]
  }, [allEvents, series, autoLabel])

  function pickSeries(s: Series | null) {
    onSeriesChange(s?.id ?? null, s)
    onEventChange(null, null) // a new (or cleared) series invalidates the pin
  }

  function pickEvent(id: number | null) {
    onEventChange(id, id === null ? null : (allEvents?.find((e) => e.id === id) ?? null))
  }

  // Creates the series up front so its real id can pin the batch (and filter
  // events); a name that already exists just selects the existing one.
  async function createSeries(name: string) {
    const trimmed = name.trim()
    if (!trimmed) return
    const existing = allSeries?.find((s) => s.name.toLowerCase() === trimmed.toLowerCase())
    if (existing) {
      pickSeries(existing)
      return
    }
    onError?.(null)
    try {
      const res = await fetch('/api/series', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: trimmed, abbreviation: null }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        onError?.(body?.message ?? `Could not create series (${res.status})`)
        return
      }
      const created = body as Series
      addSeries(created)
      pickSeries(created)
    } catch {
      onError?.('Could not reach the server.')
    }
  }

  return (
    <div className="sep-fields">
      <Combobox
        inputId={`${idPrefix}-series`}
        label="Series"
        required={required}
        loading={allSeries === null}
        placeholder="Search series…"
        emptyText="No matching series."
        options={seriesOptions}
        selectedKey={seriesId === null ? null : String(seriesId)}
        onPick={(key) => pickSeries(allSeries?.find((s) => s.id === Number(key)) ?? null)}
        onClear={() => pickSeries(null)}
        onCreate={(name) => void createSeries(name)}
        createHint="new series"
      />
      <Combobox
        inputId={`${idPrefix}-event`}
        label="Event"
        disabled={!series}
        loading={allEvents === null}
        placeholder={series ? 'Search events…' : 'Choose a series first'}
        emptyText="No events in this series yet — each import places itself."
        options={eventOptions}
        ghost
        selectedKey={series ? (eventId === null ? 'auto' : String(eventId)) : null}
        onPick={(key) => pickEvent(key === 'auto' ? null : Number(key))}
        onClear={eventId !== null ? () => pickEvent(null) : undefined}
      />
    </div>
  )
}
