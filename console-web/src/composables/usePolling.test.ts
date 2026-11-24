import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePolling } from './usePolling'

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('schedules ticks sequentially without overlapping slow async tasks', async () => {
    let calls = 0
    let resolveTask!: () => void
    const task = vi.fn(() => {
      calls += 1
      return new Promise<void>((resolve) => {
        resolveTask = resolve
      })
    })

    const poller = usePolling(task, { intervalMs: 1000, pauseWhenHidden: false })
    poller.start()

    // 第 1 次触发后长时间未完成：即使再推进 5000ms 也绝不重入第 2 次
    await vi.advanceTimersByTimeAsync(1000)
    expect(calls).toBe(1)
    expect(poller.running.value).toBe(true)

    await vi.advanceTimersByTimeAsync(5000)
    expect(calls).toBe(1)

    // 完成后再等待一个完整间隔才触发第 2 次
    resolveTask()
    await Promise.resolve()
    await Promise.resolve()
    expect(poller.running.value).toBe(false)

    await vi.advanceTimersByTimeAsync(1000)
    expect(calls).toBe(2)

    poller.stop()
  })

  it('pauses when document becomes hidden and refreshes immediately when visible again', async () => {
    const listeners = new Map<string, Set<() => void>>()
    const fakeDoc = {
      hidden: false,
      addEventListener(event: string, fn: () => void) {
        if (!listeners.has(event)) listeners.set(event, new Set())
        listeners.get(event)!.add(fn)
      },
      removeEventListener(event: string, fn: () => void) {
        listeners.get(event)?.delete(fn)
      },
      emit(event: string) {
        listeners.get(event)?.forEach((fn) => fn())
      }
    }
    vi.stubGlobal('document', fakeDoc)

    const task = vi.fn(async () => {})
    const poller = usePolling(task, { intervalMs: 5000 })
    poller.start()

    // 切后台后推进 30s 均不发请求
    fakeDoc.hidden = true
    fakeDoc.emit('visibilitychange')
    await vi.advanceTimersByTimeAsync(30000)
    expect(task).toHaveBeenCalledTimes(0)

    // 切回可见立即补拉一次并恢复调度
    fakeDoc.hidden = false
    fakeDoc.emit('visibilitychange')
    await Promise.resolve()
    expect(task).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(5000)
    expect(task).toHaveBeenCalledTimes(2)

    poller.stop()
    expect(listeners.get('visibilitychange')?.size ?? 0).toBe(0)
  })
})
