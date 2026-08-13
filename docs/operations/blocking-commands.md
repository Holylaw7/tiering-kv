# Blocking Commands

## 语义

- BLPOP/BRPOP key... timeout：秒级超时，0 = 无限；
- 先即时弹出，空则条件等待（BlockingListNotifier）；
- 返回 [key, value] 或 nil（超时）；
- 等待发生在命令执行线程，事件循环不阻塞。

## 限制

等待占用 worker 线程；通知丢失由超时兜底重试。
