import { useEffect, useMemo, useState } from 'react'
import './series-event-picker.css'
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
 * without its own lookup). The series/events fetch lives in useSeriesEvents.
 */

interface ComboOption {
  key: string
  label: string
  hint?: string
  variant?: 'auto' | 'create'
}

function SearchIcon() {
  return (
    <svg className="sep-combo-icon" width="14" height="14" viewBox="0 0 15 15" fill="none" aria-hidden="true">
      <circle cx="6.5" cy="6.5" r="4.75" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10.5 10.5 L13.5 13.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

/* ---- combobox: one typeahead, results inline so a host dialog never clips ---- */

function Combobox({
  label,
  required,
  disabled,
  loading,
  placeholder,
  emptyText,
  options,
  selectedKey,
  ghost,
  onPick,
  onClear,
  onCreate,
  inputId,
}: {
  label: string
  required?: boolean
  disabled?: boolean
  loading?: boolean
  placeholder: string
  emptyText: string
  options: ComboOption[]
  selectedKey: string | null
  ghost?: boolean
  onPick: (key: string) => void
  onClear?: () => void
  /** When set, a non-matching query offers a "+ Create '<query>'" row. */
  onCreate?: (name: string) => void
  inputId: string
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const selected = options.find((o) => o.key === selectedKey) ?? null
  // When closed, the input shows the selection; when open, it shows what's typed.
  const display = open ? query : selected?.label ?? ''

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    // Typing filters; an empty box (or a box still showing the selection) lists all.
    const filtered =
      !open || q === '' || q === selected?.label.toLowerCase()
        ? options
        : options.filter((o) => o.label.toLowerCase().includes(q) || o.hint?.toLowerCase().includes(q))
    // A novel name (no exact label match) offers a create row at the end.
    const trimmed = query.trim()
    const canCreate = !!onCreate && trimmed !== '' && !options.some((o) => o.label.toLowerCase() === trimmed.toLowerCase())
    return canCreate
      ? [...filtered, { key: '__create__', label: trimmed, variant: 'create' as const }]
      : filtered
  }, [options, query, open, selected, onCreate])

  useEffect(() => {
    setActive(0)
  }, [query, open])

  function commit(key: string) {
    if (key === '__create__' && onCreate) onCreate(query.trim())
    else onPick(key)
    setQuery('')
    setOpen(false)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) {
        setOpen(true)
        return
      }
      if (visible.length === 0) return
      const delta = e.key === 'ArrowDown' ? 1 : -1
      setActive((a) => (a + delta + visible.length) % visible.length)
    } else if (e.key === 'Enter') {
      if (open && visible[active]) {
        e.preventDefault()
        commit(visible[active].key)
      }
    } else if (e.key === 'Escape') {
      if (open) {
        // Close the list, not the host dialog.
        e.preventDefault()
        e.stopPropagation()
        setOpen(false)
        setQuery('')
      }
    }
  }

  const listId = `${inputId}-list`
  const showGhost = ghost && !open && selected?.variant === 'auto'

  return (
    <div className="sep-field">
      <label className="sep-field-label" htmlFor={inputId}>
        {label}
        {required && <span className="sep-req" aria-hidden="true">*</span>}
      </label>
      <div className={disabled ? 'sep-combo disabled' : 'sep-combo'}>
        <div className="sep-combo-row">
          <SearchIcon />
          <input
            id={inputId}
            className={showGhost ? 'sep-combo-input ghost' : 'sep-combo-input'}
            type="text"
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-activedescendant={open && visible[active] ? `${inputId}-opt-${active}` : undefined}
            aria-autocomplete="list"
            autoComplete="off"
            disabled={disabled}
            placeholder={placeholder}
            value={display}
            onChange={(e) => {
              setQuery(e.target.value)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => {
              // Let an option's mousedown land first, then close.
              window.setTimeout(() => setOpen(false), 120)
            }}
            onKeyDown={onKeyDown}
          />
          {selected && onClear && !disabled && (
            <button
              type="button"
              className="sep-combo-clear"
              aria-label={`Clear ${label.toLowerCase()}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                onClear()
                setQuery('')
              }}
            >
              ✕
            </button>
          )}
        </div>

        {open && !disabled && (
          <ul className="sep-combo-list" id={listId} role="listbox" aria-label={label}>
            {loading ? (
              <li className="sep-combo-loading" aria-label="Loading">
                <span className="skeleton" />
                <span className="skeleton" />
              </li>
            ) : visible.length === 0 ? (
              <li className="sep-combo-empty">{emptyText}</li>
            ) : (
              visible.map((o, i) => (
                <li key={o.key}>
                  <button
                    id={`${inputId}-opt-${i}`}
                    type="button"
                    role="option"
                    aria-selected={i === active}
                    className={`sep-opt${i === active ? ' active' : ''}${o.variant ? ` ${o.variant}` : ''}`}
                    onMouseEnter={() => setActive(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => commit(o.key)}
                  >
                    {o.variant === 'create' && <span className="sep-opt-plus" aria-hidden="true">+ </span>}
                    <span className="sep-opt-name">
                      {o.variant === 'create' ? `Create “${o.label}”` : o.label}
                    </span>
                    {o.variant === 'create' && <span className="sep-opt-hint">new series</span>}
                    {o.hint && <span className="sep-opt-hint">{o.hint}</span>}
                  </button>
                </li>
              ))
            )}
          </ul>
        )}
      </div>
    </div>
  )
}

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
        hint: [e.circuitName, formatEventDate(e.eventDate, e.year)].filter(Boolean).join(' · '),
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
