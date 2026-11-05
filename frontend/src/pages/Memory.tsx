import { useState } from 'react'
import { api } from '../api'
import { useAction, useResource } from '../hooks'
import { Empty, ErrorNotice } from '../components/Shared'

type Document = { id: string; title: string; body: string; updatedAt: string }
export function Memory() {
  const documents = useResource<Document[]>('/memories')
  const [selected, setSelected] = useState<Document | null>(null), [query, setQuery] = useState(''), [search, setSearch] = useState('')
  const results = useResource<{ id: string; title: string; snippet: string }[]>(`/memories/search?q=${encodeURIComponent(search || ' ')}&limit=20`)
  const action = useAction()
  const list = search ? (results.data ?? []).map(hit => documents.data?.find(d => d.id === hit.id)).filter((d): d is Document => !!d) : documents.data
  return <div className="page"><div className="page-heading"><div><h1>记忆</h1><p>留下有用的背景。你可以随时编辑或删除。</p></div><button onClick={() => setSelected({ id: crypto.randomUUID(), title: '', body: '', updatedAt: '' })}>新建记忆</button></div>
    <ErrorNotice message={documents.error || action.error || (search ? results.error : '')} />
    <form className="search-form" onSubmit={e => { e.preventDefault(); setSearch(query.trim()) }}><label className="sr-only" htmlFor="memory-search">搜索记忆</label><input id="memory-search" placeholder="搜索记忆内容" value={query} onChange={e => setQuery(e.target.value)} /><button className="quiet">搜索</button>{search ? <button type="button" className="text-button" onClick={() => { setSearch(''); setQuery('') }}>清除</button> : null}</form>
    <div className="memory-layout"><section className="document-list">{list?.map(document => <button key={document.id} className={`document-row ${selected?.id === document.id ? 'selected' : ''}`} onClick={() => setSelected(document)}><strong>{document.title}</strong><small>{document.body.slice(0, 110)}</small></button>)}
      {list && !list.length ? <Empty title={search ? '没有匹配的记忆' : '还没有记忆'}>{search ? '换一个关键词试试。' : '记录常用背景、工作习惯或项目约定。'}</Empty> : null}</section>
      <section className="memory-editor">{selected ? <MemoryEditor key={selected.id} document={selected} saved={() => { documents.reload(); results.reload() }} removed={() => { setSelected(null); documents.reload() }} /> : <Empty title="选择一条记忆">Markdown 文件是记忆的原文，搜索索引可以重新建立。</Empty>}</section></div>
    <button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api('/memories/refresh', { method: 'POST' }); documents.reload(); results.reload() })}>从本地文件刷新索引</button>
  </div>
}
function MemoryEditor({ document, saved, removed }: { document: Document; saved: () => void; removed: () => void }) {
  const [title, setTitle] = useState(document.title), [body, setBody] = useState(document.body), [deleting, setDeleting] = useState(false), [notice, setNotice] = useState('')
  const action = useAction()
  return <form onSubmit={e => { e.preventDefault(); void action.run(async () => { await api(`/memories/${document.id}`, { method: 'PUT', body: { title, body } }); setNotice('已保存到本地'); saved() }) }}>
    <label htmlFor="memory-title">标题</label><input id="memory-title" value={title} onChange={e => setTitle(e.target.value)} maxLength={256} required />
    <label htmlFor="memory-body">正文（Markdown）</label><textarea id="memory-body" value={body} onChange={e => setBody(e.target.value)} rows={15} maxLength={262144} />
    <ErrorNotice message={action.error} /><p role="status">{notice}</p><div className="actions"><button disabled={action.busy}>保存记忆</button><button type="button" className="quiet danger" onClick={() => setDeleting(true)}>删除</button></div>
    {deleting ? <div className="delete-confirm"><p>删除「{title || '这条记忆'}」的本地文件和搜索记录？</p><button type="button" className="danger" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/memories/${document.id}`, { method: 'DELETE' }); removed() })}>确认删除</button><button type="button" className="quiet" onClick={() => setDeleting(false)}>保留</button></div> : null}
  </form>
}
