import { getConnectivity, startConnectivityMonitor, subscribeConnectivity } from './connectivity'
import { listDirtyPads, saveLocalPad, type LocalPad } from './scratchpadStore'

/**
 * App-level replay of offline scratchpad ink. iOS has no Background Sync
 * API, so this is foreground-driven: on app start and every time the
 * connectivity heartbeat flips back to 'live', any dirty local pads are
 * PUT to the server — even if the user never reopens the pad. There is no
 * queue to drain: the PUT is whole-document, so a hundred offline strokes
 * collapse into one request carrying the latest local state.
 *
 * A pad whose modal is currently open is skipped (registerActivePad): the
 * modal owns its own flush, and a competing PUT from here would bump the
 * revision under it and manufacture a phantom 409.
 *
 * A real 409 here (server moved on while we were offline) is NOT resolved
 * automatically — the record is flagged `conflict` and left dirty, and the
 * modal's banner asks the user to pick a side next time the pad opens.
 */

const activePads = new Set<string>()

function key(eventId: number, owner: string): string {
  return `${eventId} ${owner}`
}

/** The open modal calls this so the background syncer leaves its pad alone. */
export function registerActivePad(eventId: number, owner: string): () => void {
  const k = key(eventId, owner)
  activePads.add(k)
  return () => activePads.delete(k)
}

let syncing = false

export async function syncDirtyPads(): Promise<void> {
  if (syncing) return
  syncing = true
  try {
    for (const pad of await listDirtyPads()) {
      if (activePads.has(key(pad.eventId, pad.owner)) || pad.conflict) continue
      await pushPad(pad)
    }
  } finally {
    syncing = false
  }
}

async function pushPad(pad: LocalPad): Promise<void> {
  try {
    const r = await fetch(`/api/events/${pad.eventId}/scratchpad`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        baseRevision: pad.baseRevision,
        pageHeight: pad.pageHeight,
        strokes: pad.strokes,
      }),
    })
    if (r.status === 409) {
      await saveLocalPad({ ...pad, conflict: true })
      return
    }
    // 401 (session expired) and 5xx: stay dirty, retry on the next 'live'
    // flip; 413 can only be resolved by erasing in the modal — also dirty.
    if (!r.ok) return
    const body = (await r.json()) as { revision: number }
    await saveLocalPad({ ...pad, dirty: false, baseRevision: body.revision })
  } catch {
    // Still offline (or the heartbeat lied); the next transition retries.
  }
}

let started = false

/** Idempotent; App.tsx calls it once. */
export function startScratchpadSync(): void {
  if (started) return
  started = true
  startConnectivityMonitor()
  let wasLive = getConnectivity().status === 'live'
  subscribeConnectivity(() => {
    const live = getConnectivity().status === 'live'
    if (live && !wasLive) void syncDirtyPads()
    wasLive = live
  })
  // Catch ink stranded by a previous session (tab killed while offline).
  void syncDirtyPads()
}
