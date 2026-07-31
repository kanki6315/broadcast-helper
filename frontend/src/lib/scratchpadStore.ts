import type { Stroke } from './scratchpad'

/**
 * IndexedDB mirror for scratchpad ink — the offline-writes half of the pad
 * (the server PUT in ScratchpadModal is the sync half, see scratchpadSync).
 * Every pen-up persists here, so the last-gasp fetch on pagehide stops being
 * the thing standing between the user and data loss: iOS killing the tab
 * costs at most the stroke in progress.
 *
 * One record per (eventId, owner). `dirty` means "has ink the server hasn't
 * accepted yet"; `conflict` means the server moved on while we were dirty and
 * a human has to pick a side (ScratchpadModal's conflict banner). `backup` is
 * a one-slot stash of whichever copy lost that choice — nothing is ever
 * destroyed, but we deliberately keep only the most recent loser.
 *
 * Every function swallows IndexedDB failures (private-mode quirks, quota,
 * eviction mid-transaction) and degrades to the pre-offline behavior: reads
 * resolve null, writes resolve without persisting. The pad must never break
 * mid-broadcast because storage did.
 */

export interface PadBackup {
  strokes: Stroke[]
  pageHeight: number
  savedAt: number
  reason: 'replaced-by-other' | 'overwritten-by-this-device'
}

export interface LocalPad {
  eventId: number
  owner: string
  strokes: Stroke[]
  pageHeight: number
  /** Server revision this local state builds on (what PUT sends as baseRevision). */
  baseRevision: number
  dirty: boolean
  conflict: boolean
  updatedAt: number
  backup?: PadBackup
}

const DB_NAME = 'pit-pass'
const STORE = 'scratchpads'

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      req.result.createObjectStore(STORE, { keyPath: ['eventId', 'owner'] })
    }
    req.onsuccess = () => {
      req.result.onclose = () => {
        dbPromise = null // evicted/closed underneath us: reopen on next call
      }
      resolve(req.result)
    }
    req.onerror = () => reject(req.error ?? new Error('IndexedDB open failed'))
    req.onblocked = () => reject(new Error('IndexedDB open blocked'))
  })
  return dbPromise
}

function requestToPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB request failed'))
  })
}

/* Pads change from the modal, the syncer, and (later) other tabs; the FAB
 * badge subscribes here to stay honest without polling. */
const listeners = new Set<() => void>()

export function subscribeLocalPads(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function notify() {
  listeners.forEach((l) => l())
}

export async function loadLocalPad(eventId: number, owner: string): Promise<LocalPad | null> {
  try {
    const db = await openDb()
    const record = await requestToPromise(
      db.transaction(STORE).objectStore(STORE).get([eventId, owner]),
    )
    return (record as LocalPad | undefined) ?? null
  } catch {
    return null
  }
}

export async function saveLocalPad(pad: LocalPad): Promise<void> {
  try {
    const db = await openDb()
    await requestToPromise(db.transaction(STORE, 'readwrite').objectStore(STORE).put(pad))
    notify()
  } catch {
    // Degrade to online-only behavior; the debounced PUT is still trying.
  }
}

export async function listDirtyPads(): Promise<LocalPad[]> {
  try {
    const db = await openDb()
    const all = await requestToPromise(db.transaction(STORE).objectStore(STORE).getAll())
    return (all as LocalPad[]).filter((p) => p.dirty)
  } catch {
    return []
  }
}
