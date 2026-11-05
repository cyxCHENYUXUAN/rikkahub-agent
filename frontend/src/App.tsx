import { useEffect, useState } from 'react'
import { api } from './api'
import { ErrorNotice } from './components/Shared'
import { Tasks } from './pages/Tasks'
import { Chat } from './pages/Chat'
import { Memory } from './pages/Memory'
import { Usage } from './pages/Usage'
import { Settings } from './pages/Settings'
import './App.css'

type View = 'chat' | 'tasks' | 'memory' | 'usage' | 'settings'
const navigation: { id: View; label: string; icon: string }[] = [
  { id: 'chat', label: '对话', icon: '◫' }, { id: 'tasks', label: '任务与待办', icon: '▤' },
  { id: 'memory', label: '记忆', icon: '▧' }, { id: 'usage', label: '用量', icon: '◷' }, { id: 'settings', label: '连接与模型', icon: '⚙' },
]
function App() {
  const [view, setView] = useState<View>('tasks')
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    api('/setup/session', { signal: controller.signal }).then(() => { setReady(true); setError('') }).catch(e => {
      if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '无法连接后端')
    })
    return () => controller.abort()
  }, [retry])
  return <div className="app-shell">
    <aside className="sidebar"><a className="brand" href="#" onClick={e => { e.preventDefault(); setView('tasks') }}><span className="brand-mark">R</span><span>RikkaHub<small>个人智能体工作区</small></span></a>
      <nav aria-label="主导航">{navigation.map(item => <button key={item.id} aria-current={view === item.id ? 'page' : undefined} onClick={() => setView(item.id)}><span aria-hidden="true">{item.icon}</span>{item.label}</button>)}</nav>
      <div className="sidebar-footer"><span className={`connection-dot ${ready ? 'connected' : ''}`} />{ready ? '本地连接已建立' : '等待本地连接'}<p>授权由你掌握，工作留在本地。</p></div>
    </aside>
    <main><header className="topbar"><span>工作区 / {navigation.find(n => n.id === view)?.label}</span><span className="local-label">本地单用户</span></header>
      {!ready ? <div className="connection-state"><h1>连接你的工作区</h1><p>请启动本地后端和前端开发服务。</p><ErrorNotice message={error} /><button onClick={() => setRetry(n => n + 1)}>重新连接</button></div>
        : view === 'tasks' ? <Tasks onSettings={() => setView('settings')} /> : view === 'chat' ? <Chat onSettings={() => setView('settings')} />
          : view === 'memory' ? <Memory /> : view === 'usage' ? <Usage /> : <Settings />}
    </main>
  </div>
}
export default App
