import { useAction, useResource } from '../hooks'
import { api, blockText } from '../api'
import type { Approval, ChatMessage } from '../api'

export function ErrorNotice({ message }: { message?: string }) {
  return message ? <p className="error" role="alert">{message}</p> : null
}
export function Empty({ title, children }: { title: string; children?: React.ReactNode }) {
  return <div className="empty"><div className="empty-mark" aria-hidden="true">◇</div><h2>{title}</h2><p>{children}</p></div>
}
export function Messages({ messages }: { messages: ChatMessage[] }) {
  return <div className="messages">{messages.filter(m => m.role !== 'SYSTEM').map(message => <article key={message.id} className={`message ${message.role.toLowerCase()}`}>
    <span className="message-role">{{ USER: '你', ASSISTANT: '助手', TOOL: '工具' }[message.role] ?? message.role}</span>
    <div>{message.content.map((b, index) => <pre key={index}>{blockText(b)}</pre>)}{message.status === 'INTERRUPTED' ? <small>本次回复已中断</small> : null}</div>
  </article>)}</div>
}
export function Approvals({ sessionId }: { sessionId: string }) {
  const approvals = useResource<Approval[]>(`/approvals?sessionId=${encodeURIComponent(sessionId)}`, 2000)
  const action = useAction()
  const pending = approvals.data?.filter(a => a.state === 'PENDING') ?? []
  return <><ErrorNotice message={approvals.error || action.error} />{pending.map(a => <section className="approval" key={a.id}>
    <h3>需要你批准：{a.toolName}</h3><p>{a.reason}</p><pre>{a.argumentsJson}</pre>
    {a.requestedDecision ? <p role="status">决定已提交，等待执行器确认。</p> : <div className="actions">
      <button disabled={action.busy} onClick={() => void action.run(async () => { await api(`/approvals/${a.id}`, { method: 'POST', body: { decision: 'allowed-once' } }); approvals.reload() })}>仅允许这一次</button>
      <button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/approvals/${a.id}`, { method: 'POST', body: { decision: 'rejected' } }); approvals.reload() })}>拒绝</button>
    </div>}
  </section>)}</>
}
