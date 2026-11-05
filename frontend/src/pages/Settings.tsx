import { useState } from 'react'
import { api } from '../api'
import type { Assistant } from '../api'
import { useAction, useResource } from '../hooks'
import { ErrorNotice } from '../components/Shared'

type Provider = { id: string; name: string; baseUrl: string; credentialRef: string | null; enabled: boolean }
type McpServer = { id: string; url: string; connected: boolean; toolCount: number }
export function Settings() {
  const providers = useResource<Provider[]>('/providers'), assistants = useResource<Assistant[]>('/assistants'), mcp = useResource<McpServer[]>('/mcp/servers')
  const capabilities = useResource<Record<string, boolean>>('/capabilities', 5000)
  const [providerName, setProviderName] = useState(''), [url, setUrl] = useState(''), [credential, setCredential] = useState('')
  const [name, setName] = useState(''), [model, setModel] = useState(''), [provider, setProvider] = useState(''), [permission, setPermission] = useState('WORKSPACE_WRITE')
  const action = useAction()
  return <div className="page settings-page"><div className="page-heading"><div><h1>连接与模型</h1><p>连接你选择的模型，明确助手的工作范围。</p></div></div><ErrorNotice message={providers.error || assistants.error || mcp.error || capabilities.error || action.error} />
    <div className="settings-grid"><section><h2>模型服务</h2>{providers.data?.map(p => <div className="configuration-row" key={p.id}><strong>{p.name}</strong><small>{p.baseUrl}</small><span>{p.credentialRef ? `凭据引用：${p.credentialRef}` : '未设置凭据引用'}</span></div>)}
      <form onSubmit={e => { e.preventDefault(); void action.run(async () => { await api('/providers', { method: 'POST', body: { name: providerName, baseUrl: url, ...(credential ? { credentialRef: credential } : {}) } }); setProviderName(''); setUrl(''); setCredential(''); providers.reload() }) }}>
        <h3>添加 OpenAI compatible 服务</h3><label htmlFor="provider-name">服务名称</label><input disabled={action.busy} id="provider-name" value={providerName} onChange={e => setProviderName(e.target.value)} maxLength={128} required />
        <label htmlFor="provider-url">API 地址</label><input disabled={action.busy} id="provider-url" type="url" placeholder="https://your-provider.example/v1" value={url} onChange={e => setUrl(e.target.value)} required />
        <label htmlFor="provider-credential">环境变量引用（可选）</label><input disabled={action.busy} id="provider-credential" placeholder="env:YOUR_API_KEY" value={credential} onChange={e => setCredential(e.target.value)} pattern="env:[A-Z_][A-Z0-9_]{0,127}" /><small>填写变量名引用；密钥值应在后端进程环境中设置。</small><button disabled={action.busy}>添加服务</button>
      </form></section>
      <section><h2>助手</h2>{assistants.data?.map(a => <div className="configuration-row" key={a.id}><strong>{a.name}</strong><span>{a.model}</span><small>{a.toolPermissionMode === 'READ_ONLY' ? '只读工作区' : '允许工作区内读写'} · 危险操作逐次审批</small></div>)}
        <form onSubmit={e => { e.preventDefault(); void action.run(async () => { await api('/assistants', { method: 'POST', body: { name, providerId: provider || providers.data?.[0]?.id, model, toolPermissionMode: permission, approvalPolicy: 'ASK' } }); setName(''); setModel(''); assistants.reload() }) }}><h3>添加助手</h3>
          <label htmlFor="assistant-name">助手名称</label><input disabled={action.busy} id="assistant-name" value={name} onChange={e => setName(e.target.value)} maxLength={128} required />
          <label htmlFor="assistant-provider">模型服务</label><select id="assistant-provider" value={provider || providers.data?.[0]?.id || ''} onChange={e => setProvider(e.target.value)} required><option value="" disabled>先添加一个模型服务</option>{providers.data?.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
          <label htmlFor="assistant-model">模型名称</label><input disabled={action.busy} id="assistant-model" value={model} onChange={e => setModel(e.target.value)} maxLength={256} required />
          <label htmlFor="assistant-permission">工作区权限</label><select id="assistant-permission" value={permission} onChange={e => setPermission(e.target.value)}><option value="WORKSPACE_WRITE">工作区内读写</option><option value="READ_ONLY">只读</option></select><button disabled={action.busy || !providers.data?.length}>添加助手</button>
        </form></section></div>
    <section className="mcp-settings"><h2>MCP 工具连接</h2><p>在本地配置中登记服务地址后，在这里连接。远端工具仍受本地权限和审批约束。</p>
      {mcp.data?.length ? mcp.data.map(s => <div className="mcp-row" key={s.id}><span><strong>{s.id}</strong><small>{s.url}</small></span><span>{s.connected ? `已连接 · ${s.toolCount} 个工具` : '未连接'}</span><button className="quiet" disabled={action.busy} onClick={() => void action.run(async () => { await api(`/mcp/servers/${s.id}/${s.connected ? 'connection' : 'connect'}`, { method: s.connected ? 'DELETE' : 'POST' }); mcp.reload(); capabilities.reload() })}>{s.connected ? '断开连接' : '连接'}</button></div>) : <p className="list-empty">尚未配置 MCP 服务。</p>}
      <div className="capabilities">{capabilities.data ? Object.entries(capabilities.data).map(([key, value]) => <span key={key} className={value ? 'available' : ''}>{({ search: '网页搜索', mcp: 'MCP 工具', orchestration: '任务编排', translation: '翻译', speech: '语音', media: '媒体' } as Record<string, string>)[key] ?? key}：{value ? '已启用' : '未配置'}</span>) : null}</div>
    </section>
  </div>
}
