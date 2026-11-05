import { useCallback, useEffect, useState } from 'react'
import { api } from './api'

export function useResource<T>(path: string, poll = 0) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  const reload = useCallback(() => setVersion(v => v + 1), [])
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function load() {
      try {
        const result = await api<T>(path, { signal: controller.signal })
        if (!controller.signal.aborted) { setData(result); setError('') }
      } catch (failure) {
        if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '连接失败，请重试')
      } finally { if (poll && !controller.signal.aborted) timer = setTimeout(load, poll) }
    }
    void load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [path, poll, version])
  return { data, error, reload }
}
export function useAction() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function run(action: () => Promise<unknown>) {
    if (busy) return
    setBusy(true); setError('')
    try { await action() } catch (failure) { setError(failure instanceof Error ? failure.message : '操作失败，请重试') }
    finally { setBusy(false) }
  }
  return { busy, error, run }
}
