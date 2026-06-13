import { createSubscriptionTask, getSubscriptionStatus } from '@/utils/service'

export interface SubscriptionScanState {
  qrcodeUrl: string
  followTaskId: string
  isSubscribed: boolean
}

const state: SubscriptionScanState = {
  qrcodeUrl: '',
  followTaskId: '',
  isSubscribed: false,
}

let initPromise: Promise<void> | null = null
let pollingTimer: ReturnType<typeof setInterval> | null = null
const listeners: Set<(s: SubscriptionScanState) => void> = new Set()

function notify() {
  listeners.forEach((fn) => fn({ ...state }))
}

function startPolling(taskId: string) {
  if (pollingTimer !== null) return

  pollingTimer = setInterval(async () => {
    try {
      const res = await getSubscriptionStatus(taskId)
      if (res.status === 1 && !state.isSubscribed) {
        state.isSubscribed = true
        if (pollingTimer !== null) {
          clearInterval(pollingTimer)
          pollingTimer = null
        }
        notify()
      }
    } catch {
      // ignore polling errors
    }
  }, 1000)
}

function initOnce(): Promise<void> {
  console.log(
    '[SubscriptionScan] initOnce called, followTaskId:',
    state.followTaskId,
    'initPromise:',
    !!initPromise,
  )
  if (state.followTaskId) {
    return Promise.resolve()
  }

  if (!initPromise) {
    initPromise = createSubscriptionTask()
      .then((res) => {
        console.log('[SubscriptionScan] createSubscriptionTask resolved:', res)
        state.qrcodeUrl = res.qrcodeUrl
        state.followTaskId = res.followTaskId
        startPolling(res.followTaskId)
        notify()
      })
      .catch((e) => {
        console.error('[SubscriptionScan] createSubscriptionTask rejected:', e)
        initPromise = null
        throw e
      })
  }

  return initPromise
}

export function useSubscriptionScan(onUpdate: (s: SubscriptionScanState) => void): () => void
export function useSubscriptionScan(): Promise<void>
export function useSubscriptionScan(
  onUpdate?: (s: SubscriptionScanState) => void,
): (() => void) | Promise<void> {
  if (onUpdate) {
    listeners.add(onUpdate)
    onUpdate({ ...state })

    if (state.isSubscribed) {
      return () => {}
    }

    return () => {
      listeners.delete(onUpdate)
    }
  }

  return initOnce()
}
