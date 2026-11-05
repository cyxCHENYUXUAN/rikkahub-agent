import { useState } from 'react'
import { useAction, useResource } from '../hooks'
import { api, blockText } from '../api'
import type { Approval, ChatMessage, Content } from '../api'

export function ErrorNotice({ message }: { message?: string }) {
  return message ? <div className="error-banner" role="alert"><span className="error-icon">⚠</span><span>{message}</span></div> : null
}

export function Empty({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-mark" aria-hidden="true">◈</div>
      <h2>{title}</h2>
      <p>{children}</p>
    </div>
  )
}

/**
 * Modern Thinking / Reasoning accordion block aligned with DeepSeek R1 and Claude thinking models.
 */
export function ThinkingBlock({ text, isLive }: { text: string; isLive?: boolean }) {
  const [userCollapsed, setUserCollapsed] = useState<boolean | null>(null)
  const collapsed = userCollapsed !== null ? userCollapsed : !isLive

  if (!text) return null

  return (
    <div className={`thinking-block ${isLive ? 'thinking-streaming' : 'thinking-done'}`}>
      <button
        type="button"
        className="thinking-header"
        onClick={() => setUserCollapsed(prev => (prev !== null ? !prev : Boolean(isLive)))}
        aria-expanded={!collapsed}
      >
        <div className="thinking-title">
          <span className={`thinking-indicator ${isLive ? 'pulsing' : ''}`} aria-hidden="true">
            {isLive ? '⚡' : '🧠'}
          </span>
          <strong>{isLive ? '深度思考中…' : '思考过程'}</strong>
          <span className="thinking-badge">{text.length} 字符</span>
        </div>
        <span className="thinking-chevron">{collapsed ? '展开 ▼' : '收起 ▲'}</span>
      </button>
      {!collapsed && (
        <div className="thinking-body">
          <pre>{text}</pre>
        </div>
      )}
    </div>
  )
}

function ToolBlockView({ block }: { block: Content }) {
  const [open, setOpen] = useState(false)
  const isTool = block.type === 'tool_call' || block.type === 'tool_result' || !!block.toolName

  if (!isTool) {
    return <pre className="content-text">{blockText(block)}</pre>
  }

  const name = block.toolName || (block.type === 'tool_call' ? '工具调用' : '工具结果')
  const details = block.text || (block.content ? block.content.map(blockText).join('\n') : '')

  return (
    <div className={`tool-pill-block ${block.isError ? 'tool-error' : ''}`}>
      <div className="tool-pill-header" onClick={() => setOpen(o => !o)} role="button" tabIndex={0}>
        <span className="tool-badge">⚙ {name}</span>
        {block.callId ? <small className="tool-call-id">{block.callId}</small> : null}
        <span className="tool-pill-toggle">{open ? '收起 ▲' : '详情 ▼'}</span>
      </div>
      {open && details ? (
        <div className="tool-pill-body">
          <pre>{details}</pre>
        </div>
      ) : null}
    </div>
  )
}

export function Messages({ messages }: { messages: ChatMessage[] }) {
  const [copiedId, setCopiedId] = useState<string | null>(null)

  const copyText = (id: string, text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopiedId(id)
      setTimeout(() => setCopiedId(null), 2000)
    }).catch(() => {})
  }

  const visibleMessages = messages.filter(m => m.role !== 'SYSTEM')

  return (
    <div className="messages-stream">
      {visibleMessages.map(message => {
        const role = message.role.toUpperCase()
        const isUser = role === 'USER'
        const isAssistant = role === 'ASSISTANT'

        const fullText = message.content.map(blockText).join('\n')

        return (
          <article
            key={message.id}
            className={`message-card ${isUser ? 'msg-user' : isAssistant ? 'msg-assistant' : 'msg-tool'}`}
          >
            <div className="message-meta">
              <span className={`role-tag ${role.toLowerCase()}`}>
                {isUser ? '用户' : isAssistant ? 'Rikka 助手' : '工具环境'}
              </span>
              {isAssistant && fullText ? (
                <button
                  type="button"
                  className="msg-copy-btn"
                  onClick={() => copyText(message.id, fullText)}
                  title="复制内容"
                >
                  {copiedId === message.id ? '✓ 已复制' : '复制'}
                </button>
              ) : null}
            </div>
            <div className="message-content">
              {message.content.map((b, index) => (
                <ToolBlockView key={index} block={b} />
              ))}
              {message.status === 'INTERRUPTED' ? (
                <div className="interrupted-notice">⚡ 本次回复已被系统安全中断</div>
              ) : null}
            </div>
          </article>
        )
      })}
    </div>
  )
}

export function Approvals({ sessionId }: { sessionId: string }) {
  const approvals = useResource<Approval[]>(`/approvals?sessionId=${encodeURIComponent(sessionId)}`, 2000)
  const action = useAction()
  const pending = approvals.data?.filter(a => a.state === 'PENDING') ?? []

  return (
    <>
      <ErrorNotice message={approvals.error || action.error} />
      {pending.map(a => (
        <section className="approval-card" key={a.id}>
          <div className="approval-header">
            <span className="approval-warning-icon">🛡</span>
            <div>
              <h3>安全授权请求：{a.toolName}</h3>
              <p>{a.reason}</p>
            </div>
          </div>
          <pre className="approval-code">{a.argumentsJson}</pre>
          {a.requestedDecision ? (
            <p className="approval-pending-status" role="status">决定已提交，等待执行器确认…</p>
          ) : (
            <div className="approval-actions">
              <button
                className="btn-primary"
                disabled={action.busy}
                onClick={() => void action.run(async () => {
                  await api(`/approvals/${a.id}`, { method: 'POST', body: { decision: 'allowed-once' } })
                  approvals.reload()
                })}
              >
                仅允许这一次
              </button>
              <button
                className="btn-danger-quiet"
                disabled={action.busy}
                onClick={() => void action.run(async () => {
                  await api(`/approvals/${a.id}`, { method: 'POST', body: { decision: 'rejected' } })
                  approvals.reload()
                })}
              >
                拒绝执行
              </button>
            </div>
          )}
        </section>
      ))}
    </>
  )
}
