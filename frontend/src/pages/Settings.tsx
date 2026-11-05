import { useState } from 'react'
import { api } from '../api'
import type { Assistant } from '../api'
import { useAction, useResource } from '../hooks'
import { ErrorNotice } from '../components/Shared'

type ProviderType = 'openai-compatible' | 'anthropic' | 'gemini'

type Provider = {
  id: string
  name: string
  type?: ProviderType
  baseUrl: string
  credentialRef: string | null
  enabled: boolean
}

type McpServer = {
  id: string
  url: string
  connected: boolean
  toolCount: number
}

type SandboxStatus = {
  activeDriver: string
  containerAvailable: boolean
  containerEngine: string
  containerVersion: string
  containerImage: string
  networkIsolated: boolean
  ptySupported: boolean
  ptyMode: string
}

const PROVIDER_PRESETS: Record<ProviderType, { label: string; placeholderUrl: string; desc: string }> = {
  'openai-compatible': {
    label: 'OpenAI Compatible (DeepSeek / Qwen / SiliconFlow / OpenAI)',
    placeholderUrl: 'https://api.deepseek.com/v1',
    desc: '标准 OpenAI 兼容协议，支持流式 SSE、多轮工具调用与 DeepSeek R1 Reasoning 思维链。',
  },
  'anthropic': {
    label: 'Anthropic Claude 原生 Messages 协议',
    placeholderUrl: 'https://api.anthropic.com/v1',
    desc: 'Claude 官方 /v1/messages 流式规范，原生支持 Claude Thinking 深度思考预算与工具结果闭环。',
  },
  'gemini': {
    label: 'Google Gemini Native 原生协议',
    placeholderUrl: 'https://generativelanguage.googleapis.com/v1beta',
    desc: 'Gemini streamGenerateContent 接口，支持 Gemini 2.0 Thought 思维流与函数调用声明。',
  },
}

export function Settings() {
  const providers = useResource<Provider[]>('/providers')
  const assistants = useResource<Assistant[]>('/assistants')
  const mcp = useResource<McpServer[]>('/mcp/servers')
  const capabilities = useResource<Record<string, boolean>>('/capabilities', 5000)
  const sandbox = useResource<SandboxStatus>('/capabilities/sandbox', 10000)

  const [providerName, setProviderName] = useState('')
  const [providerType, setProviderType] = useState<ProviderType>('openai-compatible')
  const [url, setUrl] = useState('')
  const [credential, setCredential] = useState('')

  const [name, setName] = useState('')
  const [model, setModel] = useState('')
  const [provider, setProvider] = useState('')
  const [permission, setPermission] = useState('WORKSPACE_WRITE')

  const action = useAction()

  return (
    <div className="page settings-page">
      <div className="page-heading">
        <div>
          <h1>连接与模型配置</h1>
          <p>基于 DeepSeek 多厂商架构，自由连接 OpenAI、Anthropic Claude 与 Google Gemini，掌控运行沙箱边界。</p>
        </div>
      </div>

      <ErrorNotice message={providers.error || assistants.error || mcp.error || capabilities.error || action.error} />

      <div className="settings-grid">
        {/* 模型提供商配置 */}
        <section className="settings-card">
          <div className="card-header">
            <h2>模型服务提供商 (Providers)</h2>
            <small>已配置 {providers.data?.length ?? 0} 个服务</small>
          </div>

          <div className="provider-list">
            {providers.data?.map(p => (
              <div className="configuration-row" key={p.id}>
                <div className="conf-main">
                  <strong>{p.name}</strong>
                  <span className={`provider-type-tag ${p.type ?? 'openai-compatible'}`}>
                    {p.type === 'anthropic' ? 'Anthropic' : p.type === 'gemini' ? 'Gemini' : 'OpenAI 兼容'}
                  </span>
                </div>
                <small className="conf-url">{p.baseUrl}</small>
                <span className="conf-cred">
                  {p.credentialRef ? `凭据引用：${p.credentialRef}` : '未绑定环境变量凭据'}
                </span>
              </div>
            ))}
            {providers.data?.length === 0 && (
              <p className="list-empty">尚未添加任何模型提供商，请在下方添加。</p>
            )}
          </div>

          <form
            className="styled-form"
            onSubmit={e => {
              e.preventDefault()
              void action.run(async () => {
                await api('/providers', {
                  method: 'POST',
                  body: {
                    name: providerName,
                    type: providerType,
                    baseUrl: url,
                    ...(credential ? { credentialRef: credential } : {}),
                  },
                })
                setProviderName('')
                setUrl('')
                setCredential('')
                providers.reload()
              })
            }}
          >
            <h3>+ 添加新模型服务</h3>
            
            <label htmlFor="provider-type-select">协议规范</label>
            <select
              id="provider-type-select"
              value={providerType}
              onChange={e => {
                const newType = e.target.value as ProviderType
                setProviderType(newType)
                if (!url || Object.values(PROVIDER_PRESETS).some(p => p.placeholderUrl === url)) {
                  setUrl(PROVIDER_PRESETS[newType].placeholderUrl)
                }
              }}
            >
              <option value="openai-compatible">OpenAI Compatible (DeepSeek / Qwen / SiliconFlow)</option>
              <option value="anthropic">Anthropic Claude Messages (/v1/messages)</option>
              <option value="gemini">Google Gemini Native (streamGenerateContent)</option>
            </select>
            <small className="form-hint">{PROVIDER_PRESETS[providerType].desc}</small>

            <label htmlFor="provider-name">服务标识名称</label>
            <input
              disabled={action.busy}
              id="provider-name"
              placeholder="例如：deepseek-official 或 claude-3-5"
              value={providerName}
              onChange={e => setProviderName(e.target.value)}
              maxLength={128}
              required
            />

            <label htmlFor="provider-url">API Base URL</label>
            <input
              disabled={action.busy}
              id="provider-url"
              type="url"
              placeholder={PROVIDER_PRESETS[providerType].placeholderUrl}
              value={url}
              onChange={e => setUrl(e.target.value)}
              required
            />

            <label htmlFor="provider-credential">环境变量凭据引用 (可选)</label>
            <input
              disabled={action.busy}
              id="provider-credential"
              placeholder="env:DEEPSEEK_API_KEY"
              value={credential}
              onChange={e => setCredential(e.target.value)}
              pattern="env:[A-Z_][A-Z0-9_]{0,127}"
            />
            <small className="form-hint">填入引用的环境变量名；敏感密钥由本地进程环境安全持有，不在数据库明文传输。</small>

            <button className="btn-primary" disabled={action.busy}>添加服务</button>
          </form>
        </section>

        {/* 助手定义与权限 */}
        <section className="settings-card">
          <div className="card-header">
            <h2>智能体助手 (Assistants)</h2>
            <small>已配置 {assistants.data?.length ?? 0} 个助手</small>
          </div>

          <div className="assistant-list">
            {assistants.data?.map(a => (
              <div className="configuration-row" key={a.id}>
                <div className="conf-main">
                  <strong>{a.name}</strong>
                  <span className="model-name-badge">{a.model}</span>
                </div>
                <small className="conf-permission">
                  {a.toolPermissionMode === 'READ_ONLY' ? '🔒 只读工作区模式' : '✍ 允许工作区读写'} · 敏感操作逐次审批
                </small>
              </div>
            ))}
            {assistants.data?.length === 0 && (
              <p className="list-empty">尚未配置助手角色，请在下方创建。</p>
            )}
          </div>

          <form
            className="styled-form"
            onSubmit={e => {
              e.preventDefault()
              void action.run(async () => {
                await api('/assistants', {
                  method: 'POST',
                  body: {
                    name,
                    providerId: provider || providers.data?.[0]?.id,
                    model,
                    toolPermissionMode: permission,
                    approvalPolicy: 'ASK',
                  },
                })
                setName('')
                setModel('')
                assistants.reload()
              })
            }}
          >
            <h3>+ 创建新助手</h3>
            
            <label htmlFor="assistant-name">助手名称</label>
            <input
              disabled={action.busy}
              id="assistant-name"
              placeholder="例如：架构专家 或 代码助手"
              value={name}
              onChange={e => setName(e.target.value)}
              maxLength={128}
              required
            />

            <label htmlFor="assistant-provider">关联模型服务</label>
            <select
              id="assistant-provider"
              value={provider || providers.data?.[0]?.id || ''}
              onChange={e => setProvider(e.target.value)}
              required
            >
              <option value="" disabled>请先添加或选择一个模型服务</option>
              {providers.data?.map(p => (
                <option key={p.id} value={p.id}>{p.name} ({p.baseUrl})</option>
              ))}
            </select>

            <label htmlFor="assistant-model">模型标识 (Model ID)</label>
            <input
              disabled={action.busy}
              id="assistant-model"
              placeholder="例如：deepseek-reasoner 或 claude-3-5-sonnet-20241022"
              value={model}
              onChange={e => setModel(e.target.value)}
              maxLength={256}
              required
            />

            <label htmlFor="assistant-permission">工作区执行权限</label>
            <select
              id="assistant-permission"
              value={permission}
              onChange={e => setPermission(e.target.value)}
            >
              <option value="WORKSPACE_WRITE">工作区内读写 (受限目录，破坏性操作需审批)</option>
              <option value="READ_ONLY">严格只读模式 (仅允许检查和分析，禁止改动)</option>
            </select>

            <button className="btn-primary" disabled={action.busy || !providers.data?.length}>
              创建助手
            </button>
          </form>
        </section>
      </div>

      {/* 生产沙箱与 PTY 运行环境状态 */}
      <section className="settings-card env-capabilities-card">
        <div className="card-header">
          <h2>执行沙箱与系统能力 (Runtime & Sandbox)</h2>
          <span className="driver-badge">{sandbox.data?.activeDriver ?? 'LOCAL'}</span>
        </div>
        
        <div className="capabilities-grid">
          <div className="capability-item">
            <strong>伪终端仿真 (PTY)</strong>
            <span>{sandbox.data?.ptySupported ? `✓ Windows ConPTY 就绪 (${sandbox.data.ptyMode})` : '— 标准 PIPE 管道回退'}</span>
            <small>提供真实双向 ANSI 转义流与动态尺寸伸缩能力。</small>
          </div>
          <div className="capability-item">
            <strong>容器隔离沙箱</strong>
            <span>{sandbox.data?.containerAvailable ? `✓ ${sandbox.data.containerEngine} (${sandbox.data.containerVersion})` : '— 本地进程轻量沙箱'}</span>
            <small>{sandbox.data?.containerAvailable ? '危险命令在 Docker 隔离环境受限执行。' : '未检测到 Docker/Podman，自动平滑降级为本地隔离执行。'}</small>
          </div>
          <div className="capability-item">
            <strong>MCP 外部扩展</strong>
            <span>{capabilities.data?.mcp ? '✓ MCP 2025-11-25 客户端就绪' : '— 未配置'}</span>
            <small>支持 Stdio 本地子进程与 HTTP 双重连接规范。</small>
          </div>
          <div className="capability-item">
            <strong>联网搜索 (SearXNG)</strong>
            <span>{capabilities.data?.search ? '✓ 已连接 SearXNG 实例' : '— 未配置 SEARCH_URL'}</span>
            <small>保护隐私的本地开源聚合搜索服务。</small>
          </div>
        </div>
      </section>

      {/* MCP 工具管理 */}
      <section className="settings-card mcp-settings">
        <div className="card-header">
          <h2>MCP (Model Context Protocol) 工具生态</h2>
          <small>本地配置 Stdio 或 HTTP 工具服务，扩展 Agent 现实能力</small>
        </div>

        {mcp.data?.length ? (
          <div className="mcp-list">
            {mcp.data.map(s => (
              <div className="mcp-row" key={s.id}>
                <div className="mcp-info">
                  <strong>{s.id}</strong>
                  <small>{s.url}</small>
                </div>
                <div className="mcp-status-col">
                  <span className={`mcp-badge ${s.connected ? 'connected' : ''}`}>
                    {s.connected ? `已连接 · ${s.toolCount} 个可用工具` : '未连接'}
                  </span>
                  <button
                    className="btn-quiet"
                    disabled={action.busy}
                    onClick={() => void action.run(async () => {
                      await api(`/mcp/servers/${s.id}/${s.connected ? 'connection' : 'connect'}`, {
                        method: s.connected ? 'DELETE' : 'POST',
                      })
                      mcp.reload()
                      capabilities.reload()
                    })}
                  >
                    {s.connected ? '断开' : '立即连接'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="list-empty">尚未在配置文件中登记外部 MCP 服务。您可以在 config/mcp 目录中配置 Stdio 本地子进程或 HTTP 服务。</p>
        )}
      </section>
    </div>
  )
}
