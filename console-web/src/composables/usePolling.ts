import { getCurrentScope, onScopeDispose, ref, type Ref } from 'vue'

export interface UsePollingOptions {
  /** 轮询间隔（毫秒） */
  intervalMs: number
  /** 标签页不可见时是否暂停（默认 true） */
  pauseWhenHidden?: boolean
  /** 切回可见时是否立即触发一次刷新（默认 true） */
  refreshOnVisible?: boolean
}

export interface PollingHandle {
  active: Ref<boolean>
  running: Ref<boolean>
  start: () => void
  stop: () => void
  triggerNow: () => Promise<void>
}

/**
 * O4-B：防重入递归 setTimeout 轮询 Composable。
 *
 * <p>相比裸 setInterval 具备三项保障：
 * <ul>
 *   <li>严格防重入：前一次异步请求未结束，绝不会并发重叠下一次；</li>
 *   <li>标签页后台暂停：监听 document.visibilitychange，切后台暂停、切回立即补拉；</li>
 *   <li>作用域自动清理：组件卸载时彻底清除定时器与事件监听。</li>
 * </ul>
 */
export function usePolling(
  task: () => Promise<void> | void,
  options: UsePollingOptions
): PollingHandle {
  const pauseWhenHidden = options.pauseWhenHidden !== false
  const refreshOnVisible = options.refreshOnVisible !== false
  const active = ref(false)
  const running = ref(false)

  let timer: ReturnType<typeof setTimeout> | undefined
  let visibilityBound = false

  function isHidden(): boolean {
    return typeof document !== 'undefined' && Boolean(document.hidden)
  }

  function clearTimer() {
    if (timer !== undefined) {
      clearTimeout(timer)
      timer = undefined
    }
  }

  function scheduleNext() {
    clearTimer()
    if (!active.value) return
    if (pauseWhenHidden && isHidden()) return
    timer = setTimeout(() => {
      void executeTick()
    }, options.intervalMs)
  }

  async function executeTick() {
    if (!active.value) return
    if (pauseWhenHidden && isHidden()) return
    if (running.value) return
    running.value = true
    try {
      await task()
    } finally {
      running.value = false
      scheduleNext()
    }
  }

  function onVisibilityChange() {
    if (!active.value || !pauseWhenHidden) return
    if (isHidden()) {
      clearTimer()
    } else if (refreshOnVisible) {
      clearTimer()
      void executeTick()
    } else {
      scheduleNext()
    }
  }

  function bindVisibility() {
    if (visibilityBound || !pauseWhenHidden || typeof document === 'undefined') return
    document.addEventListener('visibilitychange', onVisibilityChange)
    visibilityBound = true
  }

  function unbindVisibility() {
    if (!visibilityBound || typeof document === 'undefined') return
    document.removeEventListener('visibilitychange', onVisibilityChange)
    visibilityBound = false
  }

  function start() {
    if (active.value) return
    active.value = true
    bindVisibility()
    scheduleNext()
  }

  function stop() {
    active.value = false
    clearTimer()
    unbindVisibility()
  }

  async function triggerNow() {
    if (running.value) return
    clearTimer()
    running.value = true
    try {
      await task()
    } finally {
      running.value = false
      scheduleNext()
    }
  }

  if (getCurrentScope()) {
    onScopeDispose(stop)
  }

  return { active, running, start, stop, triggerNow }
}
