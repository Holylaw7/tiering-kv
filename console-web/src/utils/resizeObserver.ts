/**
 * O4-B：基于 ResizeObserver 的容器尺寸防抖监听器。
 * 当容器自身宽度因侧边栏折叠、抽屉开关或网格折行变化时触发回调；
 * 若运行环境缺少 ResizeObserver 则优雅回退到 window.resize。
 */
export function observeElementResize(
  element: Element,
  onResize: () => void,
  debounceMs = 50
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  const schedule = () => {
    if (disposed) return
    if (timer !== undefined) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = undefined
      if (!disposed) {
        onResize()
      }
    }, debounceMs)
  }

  let observer: ResizeObserver | null = null
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(schedule)
    observer.observe(element)
  } else if (typeof window !== 'undefined') {
    window.addEventListener('resize', schedule)
  }

  return () => {
    disposed = true
    if (timer !== undefined) {
      clearTimeout(timer)
      timer = undefined
    }
    if (observer) {
      observer.disconnect()
      observer = null
    } else if (typeof window !== 'undefined') {
      window.removeEventListener('resize', schedule)
    }
  }
}
