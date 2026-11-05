import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { Assistant, ChatMessage, Conversation } from '../api'
import { useAction, useResource } from '../hooks'
import { Approvals, Empty, ErrorNotice, Messages } from '../components/Shared'

export function Chat({ onSettings }: { onSettings: () => void }) {
  const conversations = useResource<Conversation[]>('/conversations', 4000)
  const assistants = useResource<Assistant[]>('/assistants')
  const [assistant, setAssistant] = useState(''), [selected, setSelected] = useState('')
  const action = useAction()
  const profile = assistant || assistants.data?.[0]?.id || ''
  const current = conversations.data?.find(c => c.id === selected)
  return <div className="page"><div className="page-heading"><div><h1>对话</h1><p>从一句话开始，工具操作由你审批。</p></div></div><ErrorNotice message={conversations.error || assistants.error || action.error} />
    <div className="chat-layout"><aside className="conversation-list"><select aria-label="对话助手" value={profile} onChange={e => setAssistant(e.target.value)}><option disabled value="">选择助手</option>{assistants.data?.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select>
      <button disabled={action.busy || !profile} onClick={() => void action.run(async () => { const conversation = await api<Conversation>('/conversations', { method: 'POST', body: { assistantId: profile } }); setSelected(conversation.id); conversations.reload() })}>新建对话</button>
      {!profile ? <button className="text-button" onClick={onSettings}>配置模型和助手</button> : null}
      {conversations.data?.map(c => <button key={c.id} className={`conversation-row ${selected === c.id ? 'selected' : ''}`} onClick={() => setSelected(c.id)}>{c.title}{c.status === 'ARCHIVED' ? <small>已归档</small> : null}</button>)}
    </aside><section className="chat-detail">{current ? <ChatDetail key={current.id} conversation={current} refresh={conversations.reload} /> : <Empty title="留出一处思考的空间">新建或选择一个对话，开始和你的助手协作。</Empty>}</section></div>
  </div>
}
function ChatDetail({ conversation, refresh }: { conversation: Conversation; refresh: () => void }) {
  const messages = useResource<ChatMessage[]>(`/conversations/${conversation.id}/messages`, 5000)
  const [text, setText] = useState(''), [delta, setDelta] = useState(''), [turn, setTurn] = useState<number | null>(null), [connection, setConnection] = useState('正在连接实时消息…')
  const key = useRef(crypto.randomUUID()), action = useAction()
  const reloadMessages = messages.reload
  useEffect(() => {
    const stream = new EventSource(`/api/v1/conversations/${conversation.id}/stream?after=0`)
    stream.onopen = () => setConnection('实时消息已连接')
    stream.onerror = () => setConnection('实时连接暂时断开，正在重连；已保存的消息仍可查看。')
    stream.addEventListener('assistant.delta', e => { const data = JSON.parse((e as MessageEvent).data) as { text: string }; setDelta(value => value + data.text) })
    stream.addEventListener('session.committed', e => {
      const data = JSON.parse((e as MessageEvent).data) as { events: { data: { type: string; turn?: number } }[] }
      for (const event of data.events) {
        if (event.data.type === 'turn/start') setTurn(event.data.turn ?? null)
        if (event.data.type === 'turn/end') setTurn(null)
        if (event.data.type === 'assistant/message') setDelta('')
      }
      reloadMessages()
    })
    stream.addEventListener('turn.ended', () => { setTurn(null); setDelta(''); reloadMessages() })
    return () => stream.close()
  }, [conversation.id, reloadMessages])
  const archived = conversation.status === 'ARCHIVED'
  return <><div className="section-title"><h2>{conversation.title}</h2><div className="actions">{!archived ? <>
    <button className="quiet" disabled={action.busy || turn !== null} onClick={() => void action.run(async () => { await api(`/sessions/${conversation.sessionId}/compaction`, { method: 'POST' }); messages.reload() })}>压缩上下文</button>
    <button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/conversations/${conversation.id}`, { method: 'DELETE' }); refresh() })}>归档</button></> : null}</div></div>
    <small role="status">{connection}</small><ErrorNotice message={messages.error || action.error} /><Messages messages={messages.data ?? []} />
    <ToolOutputs sessionId={conversation.sessionId} />
    {delta ? <article className="message assistant live-message"><span className="message-role">助手</span><pre>{delta}</pre></article> : null}
    {!archived ? <Approvals sessionId={conversation.sessionId} /> : null}
    {turn !== null ? <div className="turn-status"><span>正在处理第 {turn} 轮</span><button className="quiet danger" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/conversations/${conversation.id}/turns/${turn}/cancel`, { method: 'POST' }) })}>停止本轮</button></div> : null}
    {!archived ? <form className="chat-compose" onSubmit={e => { e.preventDefault(); void action.run(async () => {
      await api(`/conversations/${conversation.id}/messages`, { method: 'POST', key: key.current, body: { content: [{ type: 'text', text }] } }); setText(''); key.current = crypto.randomUUID(); messages.reload()
    }) }}><label className="sr-only" htmlFor="chat-input">消息</label><textarea disabled={action.busy} id="chat-input" placeholder="写下你的想法…" value={text} maxLength={262144} onChange={e => { setText(e.target.value); key.current = crypto.randomUUID() }} required /><div><small>工具审批只对本次调用生效。</small><button disabled={action.busy || !text.trim()}>发送消息</button></div></form> : null}
  </>
}


type ToolOutput = { reference: string; retainedBytes: number; sourceBytes: number; status: string; available: boolean }
type OutputUsage = { totalBytes: number; totalLimitBytes: number; sessionBytes: number; sessionLimitBytes: number; outputs: ToolOutput[] }

function formatBytes(bytes: number) {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} B`
}

function ToolOutputs({ sessionId }: { sessionId: string }) {
  const path = `/sessions/${encodeURIComponent(sessionId)}/tool-outputs`
  const usage = useResource<OutputUsage>(path, 5000)
  const action = useAction()
  const [preview, setPreview] = useState<{ reference: string; text: string; partial: boolean } | null>(null)
  const totals = usage.data
  return <details className="execution-history">
    <summary>工具输出{totals ? ` · 本会话 ${formatBytes(totals.sessionBytes)}` : ''}</summary>
    <ErrorNotice message={usage.error || action.error} />
    {totals ? <>
      <p>本会话 {formatBytes(totals.sessionBytes)} / {formatBytes(totals.sessionLimitBytes)} · 全部会话 {formatBytes(totals.totalBytes)} / {formatBytes(totals.totalLimitBytes)}</p>
      {totals.outputs.length === 0 ? <p>暂无单独保存的工具输出。</p> : <ul>{totals.outputs.map(output => <li key={output.reference}>
        <code>{output.reference}</code>
        <p>{formatBytes(output.retainedBytes)}{output.sourceBytes >= 0 && output.sourceBytes !== output.retainedBytes ? ` / ${formatBytes(output.sourceBytes)}` : ''}
          {!output.available ? ' · 文件缺失或不可读' : ''}
          {output.status === 'QUOTA_REACHED' ? ' · 已按配额截断' : ''}
          {output.status === 'SOURCE_FAILED' ? ' · 源输出读取中断' : ''}</p>
        {output.available ? <button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => {
          setPreview(null)
          const response = await fetch(`/api/v1${path}/${output.reference}?limit=32768`, { credentials: 'same-origin' })
          if (!response.ok) {
            usage.reload()
            throw new Error('无法读取该工具输出，文件可能已缺失或损坏。')
          }
          const bytes = await response.arrayBuffer()
          setPreview({ reference: output.reference, text: new TextDecoder().decode(bytes), partial: bytes.byteLength < output.retainedBytes })
        })}>预览输出</button> : null}
        {preview?.reference === output.reference && output.available ? <div className="messages">
          {preview.partial ? <small>仅预览前 32 KiB</small> : null}
          <pre className="task-note">{preview.text}</pre>
        </div> : null}
      </li>)}</ul>}
    </> : null}
  </details>
}
