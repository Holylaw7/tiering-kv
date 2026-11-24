import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { observeElementResize } from '@/utils/resizeObserver'

describe('ChartPanel resize observer', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('debounces rapid ResizeObserver callbacks and disconnects cleanly on dispose', () => {
    let trigger!: () => void
    const observe = vi.fn()
    const disconnect = vi.fn()

    class FakeResizeObserver {
      constructor(cb: ResizeObserverCallback) {
        trigger = () => cb([], this as unknown as ResizeObserver)
      }
      observe = observe
      unobserve = vi.fn()
      disconnect = disconnect
    }

    vi.stubGlobal('ResizeObserver', FakeResizeObserver)

    const fakeEl = {} as Element
    const onResize = vi.fn()
    const stop = observeElementResize(fakeEl, onResize, 50)

    expect(observe).toHaveBeenCalledWith(fakeEl)

    // 50ms 窗口内连续触发多次容器尺寸变化（如侧边栏折叠动画）仅执行一次 resize
    trigger()
    vi.advanceTimersByTime(20)
    trigger()
    vi.advanceTimersByTime(20)
    trigger()
    expect(onResize).toHaveBeenCalledTimes(0)

    vi.advanceTimersByTime(50)
    expect(onResize).toHaveBeenCalledTimes(1)

    // 卸载前排队的防抖回调必须取消，且 disconnect 被调用
    trigger()
    stop()
    vi.advanceTimersByTime(100)
    expect(disconnect).toHaveBeenCalledTimes(1)
    expect(onResize).toHaveBeenCalledTimes(1)
  })

  it('falls back to window resize event when ResizeObserver is unavailable', () => {
    vi.stubGlobal('ResizeObserver', undefined)
    const listeners = new Map<string, Set<() => void>>()
    const fakeWindow = {
      addEventListener(event: string, fn: () => void) {
        if (!listeners.has(event)) listeners.set(event, new Set())
        listeners.get(event)!.add(fn)
      },
      removeEventListener(event: string, fn: () => void) {
        listeners.get(event)?.delete(fn)
      }
    }
    vi.stubGlobal('window', fakeWindow)

    const onResize = vi.fn()
    const stop = observeElementResize({} as Element, onResize, 50)

    expect(listeners.get('resize')?.size).toBe(1)
    listeners.get('resize')?.forEach((fn) => fn())
    vi.advanceTimersByTime(50)
    expect(onResize).toHaveBeenCalledTimes(1)

    stop()
    expect(listeners.get('resize')?.size).toBe(0)
  })
})
