import { useEffect, useRef, useState } from 'react'
import { api, labels } from '../api'
import type { Assistant, ChatMessage, Snapshot, Ticket } from '../api'
import { useAction, useResource } from '../hooks'
import { Approvals, Empty, ErrorNotice, Messages } from '../components/Shared'

export function Tasks({ onSettings }: { onSettings: () => void }) {
  const state = useResource<Snapshot>('/orchestration', 2000)
  const assistants = useResource<Assistant[]>('/assistants')
  const [assistant, setAssistant] = useState(''), [draft, setDraft] = useState(''), [selected, setSelected] = useState(''), [filter, setFilter] = useState('active')
  const requestId = useRef(crypto.randomUUID())
  const action = useAction()
  const currentAssistant = assistant || assistants.data?.[0]?.id || ''
  const evaluations = Object.values(state.data?.evals ?? {}).filter(e => e.decision === 'OBSERVE')
  const tickets = Object.values(state.data?.tickets ?? {}).filter(t => filter === 'all' || t.status !== 'ENDED')
  const ticket = state.data?.tickets[selected]
  return <div className="page tasks-page"><div className="page-heading"><div><h1>任务与待办</h1><p>先看清任务，再决定让智能体做什么。</p></div><span className="pill">默认观察模式</span></div>
    <ErrorNotice message={state.error || assistants.error || action.error} />
    <form className="task-compose" onSubmit={e => { e.preventDefault(); void action.run(async () => {
      await api('/orchestration/triggers', { method: 'POST', body: { source: 'manual', externalId: requestId.current, threadKey: requestId.current, text: draft } }); setDraft(''); requestId.current = crypto.randomUUID(); state.reload()
    }) }}><label htmlFor="new-task">记录一个任务</label><div className="input-row"><input disabled={action.busy} id="new-task" value={draft} onChange={e => { setDraft(e.target.value); requestId.current = crypto.randomUUID() }} placeholder="希望智能体完成什么？" maxLength={262144} required /><button disabled={action.busy || !draft.trim()}>加入待办</button></div><small>加入后等待你接单确认，不会立即调用模型。</small></form>
    <div className="task-layout"><section className="task-list"><div className="section-title"><h2>待决定 <span>{evaluations.length}</span></h2><select aria-label="执行助手" value={currentAssistant} onChange={e => setAssistant(e.target.value)}><option value="" disabled>选择执行助手</option>{assistants.data?.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></div>
      {!currentAssistant ? <p className="inline-help">接单前需要配置模型和助手。<button className="text-button" onClick={onSettings}>前往配置</button></p> : null}
      {!state.data && !state.error ? <p role="status">正在读取任务…</p> : null}
      {evaluations.map(evaluation => { const trigger = state.data?.triggers[evaluation.triggerId]; return <article className="evaluation-row" key={evaluation.id}>
        <div><span className="eyebrow">{trigger?.source === 'manual' ? '手动记录' : trigger?.source}</span><p>{trigger?.text}</p><small>{evaluation.reason}</small></div>
        <div className="actions"><button disabled={action.busy || !currentAssistant} onClick={() => void action.run(async () => {
          const created = await api<Ticket>(`/orchestration/evals/${evaluation.id}/accept`, { method: 'POST', body: { assistantId: currentAssistant } }); setSelected(created.id); state.reload()
        })}>接单并执行</button><button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/orchestration/evals/${evaluation.id}/resolve`, { method: 'POST', body: { decision: 'IGNORE' } }); state.reload() })}>忽略</button></div>
      </article> })}
      {state.data && !evaluations.length ? <p className="list-empty">没有等待接单的任务。</p> : null}
      <div className="section-title ticket-section"><h2>任务记录</h2><select aria-label="任务筛选" value={filter} onChange={e => setFilter(e.target.value)}><option value="active">进行中和待处理</option><option value="all">全部任务</option></select></div>
      {tickets.map(t => <button className={`ticket-row ${selected === t.id ? 'selected' : ''}`} key={t.id} onClick={() => setSelected(t.id)}><span className={`status-dot ${t.status.toLowerCase()}`} /><span><strong>{t.title}</strong><small>{t.note}</small></span><span className="status-label">{labels[t.status]}</span></button>)}
      {state.data && !tickets.length ? <p className="list-empty">接单后，执行进度和结果会显示在这里。</p> : null}
    </section><section className="task-detail" aria-label="任务详情">{ticket && state.data ? <TicketDetail key={ticket.id} ticket={ticket} state={state.data} refresh={state.reload} /> : <Empty title="选择一个任务">查看执行记录、处理审批，或补充信息让任务继续。</Empty>}</section></div>
    <FailedWork />
  </div>
}
function TicketDetail({ ticket, state, refresh }: { ticket: Ticket; state: Snapshot; refresh: () => void }) {
  const messages = useResource<ChatMessage[]>(`/orchestration/tickets/${ticket.id}/messages`, 2000)
  const [text, setText] = useState(''), requestId = useRef(crypto.randomUUID()), action = useAction()
  const mandate = state.mandates[ticket.mandateId]
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [])
  const assignments = Object.values(state.assignments).filter(a => a.ticketId === ticket.id)
  const active = assignments.some(a => a.status !== 'ENDED')
  const liveMandate = mandate && !mandate.revoked && new Date(mandate.expiresAt).getTime() > now
  return <><div className="detail-heading"><span className={`pill ${ticket.status.toLowerCase()}`}>{labels[ticket.status]}</span><h2>{ticket.title}</h2><p>授权版本 {mandate?.version} · {mandate?.revoked ? '已撤回' : liveMandate ? '有效' : '已过期'}</p></div>
    <ErrorNotice message={action.error || messages.error} /><p className="task-note">{ticket.note}</p>
    <div className="actions">{ticket.status !== 'ENDED' ? <button className="danger quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/orchestration/tickets/${ticket.id}/stop`, { method: 'POST' }); refresh() })}>停止并撤回授权</button> : null}
      {ticket.status === 'READY' && !active ? <button disabled={action.busy} onClick={() => void action.run(async () => { await api(`/orchestration/tickets/${ticket.id}/close`, { method: 'POST' }); refresh() })}>验收完成</button> : null}</div>
    {active ? <Approvals sessionId={`worker-${ticket.id}`} /> : null}<Messages messages={messages.data ?? []} />
    <details className="execution-history"><summary>执行记录 ({assignments.length})</summary>{assignments.map(a => <p key={a.id}>{labels[a.status]} · {labels[a.report] ?? a.report}<small>{a.note}</small></p>)}</details>
    {ticket.status !== 'ENDED' && liveMandate && !active ? <form className="resume-form" onSubmit={e => { e.preventDefault(); void action.run(async () => {
      await api(`/orchestration/tickets/${ticket.id}/messages`, { method: 'POST', key: requestId.current, body: { text } }); setText(''); requestId.current = crypto.randomUUID(); refresh()
    }) }}><label htmlFor="resume-task">补充信息并继续</label><textarea disabled={action.busy} id="resume-task" value={text} onChange={e => { setText(e.target.value); requestId.current = crypto.randomUUID() }} required maxLength={262144} placeholder="有中断时，请先确认工作目录和外部状态。" /><button disabled={action.busy || !text.trim()}>使用当前授权继续</button></form> : null}
  </>
}
function FailedWork() {
  const todos = useResource<{ effects: { id: string; error: string }[]; outbox: { id: string; error: string }[] }>('/orchestration/todos', 4000)
  const action = useAction()
  const items = [...(todos.data?.effects ?? []).map(item => ({ ...item, queue: 'effects' })), ...(todos.data?.outbox ?? []).map(item => ({ ...item, queue: 'outbox' }))]
  return <><ErrorNotice message={todos.error || action.error} />{items.length ? <section className="failed-work"><h2>需要检查的操作</h2><p>这些操作未自动重试，请检查相关任务和外部状态。</p>{items.map(item => <div key={item.id}><p>{item.error}<small>{item.id}</small></p><button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => {
    await api(`/orchestration/work/${item.queue}/${item.id}/acknowledge`, { method: 'POST', body: { note: '用户已检查相关任务与外部状态，确认无需自动重试。' } }); todos.reload()
  })}>已检查，不再提示</button></div>)}</section> : null}</>
}
