import { useCallback, useEffect, useRef, useState, type MutableRefObject } from 'react'

/**
 * The "Broadcast notes" section shared by the driver and team modals:
 * autosaves on blur, exposes a flush through `flushRef` so the host modal can
 * persist a dirty draft when it closes, and retries a failed save on the next
 * flush. `initial` changes re-seed the draft (profile refetch).
 */
export default function NotesSection({
  initial,
  save,
  flushRef,
}: {
  initial: string
  save: (text: string) => Promise<void>
  flushRef: MutableRefObject<() => void>
}) {
  const [notes, setNotes] = useState(initial)
  const stateRef = useRef({ current: initial, saved: initial })
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const statusTimer = useRef<number | undefined>(undefined)

  useEffect(() => {
    setNotes(initial)
    stateRef.current = { current: initial, saved: initial }
    setStatus('idle')
  }, [initial])

  const flush = useCallback(() => {
    const { current, saved } = stateRef.current
    if (current.trim() === saved.trim()) return
    stateRef.current.saved = current
    setStatus('saving')
    save(current)
      .then(() => {
        setStatus('saved')
        window.clearTimeout(statusTimer.current)
        statusTimer.current = window.setTimeout(() => setStatus('idle'), 1600)
      })
      .catch(() => {
        stateRef.current.saved = saved // retry on next blur/close
        setStatus('error')
      })
  }, [save])

  useEffect(() => {
    flushRef.current = flush
    return () => {
      flushRef.current = () => {}
    }
  }, [flush, flushRef])

  return (
    <section className="dm-notes" aria-label="Broadcast notes">
      <div className="dm-notes-head">
        <h3>Broadcast notes</h3>
        <span
          className={`dm-notes-status${status === 'idle' ? '' : ' show'}${
            status === 'error' ? ' error' : ''
          }`}
          role="status"
        >
          {status === 'saving' && 'Saving…'}
          {status === 'saved' && 'Saved'}
          {status === 'error' && 'Save failed — will retry'}
        </span>
      </div>
      <textarea
        value={notes}
        placeholder="Pronunciation quirks, storylines, history — anything worth saying on air. Saves automatically."
        onChange={(e) => {
          setNotes(e.target.value)
          stateRef.current.current = e.target.value
        }}
        onBlur={flush}
      />
    </section>
  )
}
