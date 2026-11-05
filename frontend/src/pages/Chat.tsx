import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { Assistant, ChatMessage, Conversation } from '../api'
import { useAction, useResource } from '../hooks'
import { Approvals, Empty, ErrorNotice, Messages, ThinkingBlock } from '../components/Shared'

export function Chat({ onSettings }: { onSettings: () => void }) {
  const conversations = useResource<Conversation[]>('/conversations', 4000)
  const assistants = useResource<Assistant[]>('/assistants')
  const [assistant, setAssistant] = useState(''), [selected, setSelected] = useState('')
  const action = useAction()
  const profile = assistant || assistants.data?.[0]?.id || ''
  const current = conversations.data?.find(c => c.id === selected)

  return (
    <div className="page chat-page">
      <div className="page-heading">
        <div>
          <h1>对话与协同</h1>
          <p>基于 DeepSeek Harness 架构，从对话开启思考，每一个高危工具调用皆在掌控之中。</p>
        </div>
      </div>
      <ErrorNotice message={conversations.error || assistants.error || action.error} />
      <div className="chat-layout">
        <aside className="conversation-list">
          <div className="assistant-select-wrap">
            <label htmlFor="assistant-picker">对话助手</label>
            <select
              id="assistant-picker"
              aria-label="对话助手"
              value={profile}
              onChange={e => setAssistant(e.target.value)}
            >
              <option disabled value="">选择助手</option>
              {assistants.data?.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <button
            className="btn-primary new-chat-btn"
            disabled={action.busy || !profile}
            onClick={() => void action.run(async () => {
              const conversation = await api<Conversation>('/conversations', { method: 'POST', body: { assistantId: profile } })
              setSelected(conversation.id)
              conversations.reload()
            })}
          >
            + 新建对话
          </button>
          {!profile ? <button className="text-button" onClick={onSettings}>⚙ 前往配置模型与助手</button> : null}
          <div className="conversation-items">
            {conversations.data?.map(c => (
              <button
                key={c.id}
                className={`conversation-row ${selected === c.id ? 'selected' : ''}`}
                onClick={() => setSelected(c.id)}
              >
                <span className="conv-title">{c.title || '新对话'}</span>
                {c.status === 'ARCHIVED' ? <small className="conv-status">已归档</small> : null}
              </button>
            ))}
          </div>
        </aside>
        <section className="chat-detail">
          {current ? (
            <ChatDetail key={current.id} conversation={current} refresh={conversations.reload} />
          ) : (
            <Empty title="留出一处思考的空间">新建或选择左侧对话，与本地部署的智能体开始协作。</Empty>
          )}
        </section>
      </div>
    </div>
  )
}

function ChatDetail({ conversation, refresh }: { conversation: Conversation; refresh: () => void }) {
  const messages = useResource<ChatMessage[]>(`/conversations/${conversation.id}/messages`, 5000)
  const [text, setText] = useState('')
  const [delta, setDelta] = useState('')
  const [reasoning, setReasoning] = useState('')
  const [turn, setTurn] = useState<number | null>(null)
  const [connection, setConnection] = useState('正在连接实时消息…')
  const key = useRef(crypto.randomUUID())
  const action = useAction()
  const reloadMessages = messages.reload

  useEffect(() => {
    const stream = new EventSource(`/api/v1/conversations/${conversation.id}/stream?after=0`)
    stream.onopen = () => setConnection('实时通道已连接')
    stream.onerror = () => setConnection('实时连接暂时重连中；已保存历史消息可正常查看。')

    stream.addEventListener('assistant.delta', e => {
      const data = JSON.parse((e as MessageEvent).data) as { text: string }
      setDelta(value => value + data.text)
    })

    stream.addEventListener('assistant.reasoning', e => {
      const data = JSON.parse((e as MessageEvent).data) as { text: string }
      setReasoning(value => value + data.text)
    })

    stream.addEventListener('session.committed', e => {
      const data = JSON.parse((e as MessageEvent).data) as { events: { data: { type: string; turn?: number } }[] }
      for (const event of data.events) {
        if (event.data.type === 'turn/start') setTurn(event.data.turn ?? null)
        if (event.data.type === 'turn/end') setTurn(null)
        if (event.data.type === 'assistant/message') {
          setDelta('')
          setReasoning('')
        }
      }
      reloadMessages()
    })

    stream.addEventListener('turn.ended', () => {
      setTurn(null)
      setDelta('')
      setReasoning('')
      reloadMessages()
    })

    return () => stream.close()
  }, [conversation.id, reloadMessages])

  const archived = conversation.status === 'ARCHIVED'

  return (
    <>
      <div className="section-title">
        <div>
          <h2>{conversation.title || '当前对话'}</h2>
          <small className="connection-indicator" role="status">● {connection}</small>
        </div>
        <div className="actions">
          {!archived ? (
            <>
              <button
                className="btn-quiet"
                title="压缩上下文释放模型预算"
                disabled={action.busy || turn !== null}
                onClick={() => void action.run(async () => {
                  await api(`/sessions/${conversation.sessionId}/compaction`, { method: 'POST' })
                  messages.reload()
                })}
              >
                压缩上下文
              </button>
              <button
                className="btn-quiet"
                disabled={action.busy}
                onClick={() => void action.run(async () => {
                  await api(`/conversations/${conversation.id}`, { method: 'DELETE' })
                  refresh()
                })}
              >
                归档
              </button>
            </>
          ) : null}
        </div>
      </div>

      <ErrorNotice message={messages.error || action.error} />
      <Messages messages={messages.data ?? []} />

      {/* 实时流式响应与思考状态 */}
      {(delta || reasoning) ? (
        <article className="message-card msg-assistant live-message">
          <div className="message-meta">
            <span className="role-tag assistant">Rikka 助手</span>
            <span className="live-pulsing-tag">● 实时生成中</span>
          </div>
          <div className="message-content">
            {reasoning ? <ThinkingBlock text={reasoning} isLive={!delta} /> : null}
            {delta ? <pre className="content-text">{delta}</pre> : null}
          </div>
        </article>
      ) : null}

      <ToolOutputs sessionId={conversation.sessionId} />

      {!archived ? <Approvals sessionId={conversation.sessionId} /> : null}

      {turn !== null ? (
        <div className="turn-status">
          <span className="turn-label">正在处理第 {turn} 轮对话…</span>
          <button
            className="btn-danger-quiet"
            disabled={action.busy}
            onClick={() => void action.run(async () => {
              await api(`/conversations/${conversation.id}/turns/${turn}/cancel`, { method: 'POST' })
            })}
          >
            停止本轮
          </button>
        </div>
      ) : null}

      {!archived ? (
        <form
          className="chat-compose"
          onSubmit={e => {
            e.preventDefault()
            void action.run(async () => {
              await api(`/conversations/${conversation.id}/messages`, {
                method: 'POST',
                key: key.current,
                body: { content: [{ type: 'text', text }] },
              })
              setText('')
              key.current = crypto.randomUUID()
              messages.reload()
            })
          }}
        >
          <label className="sr-only" htmlFor="chat-input">消息输入</label>
          <textarea
            disabled={action.busy}
            id="chat-input"
            placeholder="写下你的想法、指令或代码需求… (Enter 发送)"
            value={text}
            maxLength={262144}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                if (text.trim() && !action.busy) {
                  e.currentTarget.form?.requestSubmit()
                }
              }
            }}
            onChange={e => {
              setText(e.target.value)
              key.current = crypto.randomUUID()
            }}
            required
          />
          <div className="compose-footer">
            <small>敏感文件与进程操作将暂停以等待您的单次显式审批。</small>
            <button className="btn-primary" disabled={action.busy || !text.trim()}>
              发送消息
            </button>
          </div>
        </form>
      ) : null}
    </>
  )
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
  return (
    <details className="execution-history">
      <summary>工具输出捕获{totals ? ` · 本会话 ${formatBytes(totals.sessionBytes)}` : ''}</summary>
      <ErrorNotice message={usage.error || action.error} />
      {totals ? (
        <>
          <p className="tool-usage-quota">
            本会话 {formatBytes(totals.sessionBytes)} / {formatBytes(totals.sessionLimitBytes)} · 全部会话 {formatBytes(totals.totalBytes)} / {formatBytes(totals.totalLimitBytes)}
          </p>
          {totals.outputs.length === 0 ? (
            <p className="list-empty">暂无单独捕获落盘的工具输出。</p>
          ) : (
            <ul className="tool-outputs-list">
              {totals.outputs.map(output => (
                <li key={output.reference}>
                  <code>{output.reference}</code>
                  <p>
                    {formatBytes(output.retainedBytes)}
                    {output.sourceBytes >= 0 && output.sourceBytes !== output.retainedBytes ? ` / ${formatBytes(output.sourceBytes)}` : ''}
                    {!output.available ? ' · 文件缺失或不可读' : ''}
                    {output.status === 'QUOTA_REACHED' ? ' · 已按配额截断' : ''}
                    {output.status === 'SOURCE_FAILED' ? ' · 源输出读取中断' : ''}
                  </p>
                  {output.available ? (
                    <button
                      className="btn-quiet"
                      disabled={action.busy}
                      onClick={() => void action.run(async () => {
                        setPreview(null)
                        const response = await fetch(`/api/v1${path}/${output.reference}?limit=32768`, { credentials: 'same-origin' })
                        if (!response.ok) {
                          usage.reload()
                          throw new Error('无法读取该工具输出，文件可能已缺失或损坏。')
                        }
                        const bytes = await response.arrayBuffer()
                        setPreview({ reference: output.reference, text: new TextDecoder().decode(bytes), partial: bytes.byteLength < output.retainedBytes })
                      })}
                    >
                      预览输出
                    </button>
                  ) : null}
                  {preview?.reference === output.reference && output.available ? (
                    <div className="output-preview-card">
                      {preview.partial ? <small>仅预览前 32 KiB</small> : null}
                      <pre className="task-note">{preview.text}</pre>
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}
    </details>
  )
}
