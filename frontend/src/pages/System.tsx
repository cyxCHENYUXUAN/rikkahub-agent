import { useState } from 'react'
import { api } from '../api'
import { useAction, useResource } from '../hooks'
import { ErrorNotice } from '../components/Shared'

type BackupInfo = {
  filename: string
  sizeBytes: number
  sha256: string
  totalFiles: number
  createdAt: string
}

type RestoreResult = {
  success: boolean
  restoredFiles: number
  message: string
}

type SubagentTask = {
  taskId: string
  parentSessionId: string
  subagentSessionId: string
  assistantId: string
  instruction: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'
  result: string | null
  createdAt: string
}

function formatBytes(bytes: number) {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} B`
}

export function System() {
  const backups = useResource<BackupInfo[]>('/system/backups', 8000)
  const subagentTasks = useResource<SubagentTask[]>('/orchestration/subagents', 5000)
  const [restoredMsg, setRestoredMsg] = useState<string | null>(null)
  const [confirmingRestore, setConfirmingRestore] = useState<string | null>(null)
  const action = useAction()

  return (
    <div className="page system-page">
      <div className="page-heading">
        <div>
          <h1>系统运维与协同网络</h1>
          <p>基于 DeepSeek 容灾与协同规范：无锁快照热备、受控在线恢复与 Subagent 独立子任务监控。</p>
        </div>
      </div>

      <ErrorNotice message={backups.error || subagentTasks.error || action.error} />
      {restoredMsg ? <div className="success-banner" role="status">✓ {restoredMsg}</div> : null}

      <div className="system-grid">
        {/* 快照与容灾备份 */}
        <section className="settings-card">
          <div className="card-header">
            <div>
              <h2>容灾快照与热备归档 (.rkpack)</h2>
              <small>基于 SQLite VACUUM INTO 无锁热备，打包数据库、记忆、技能与工作区，脱敏排除私钥</small>
            </div>
            <button
              className="btn-primary"
              disabled={action.busy}
              onClick={() => void action.run(async () => {
                const newBackup = await api<BackupInfo>('/system/backup', { method: 'POST' })
                setRestoredMsg(`成功创建快照归档：${newBackup.filename}`)
                backups.reload()
              })}
            >
              + 立即创建热备快照
            </button>
          </div>

          <div className="backup-list">
            {backups.data?.map(b => (
              <div className="backup-row" key={b.filename}>
                <div className="backup-meta">
                  <strong>{b.filename}</strong>
                  <div className="backup-details">
                    <span>{formatBytes(b.sizeBytes)}</span>
                    <span>· {b.totalFiles} 个文件</span>
                    <span className="hash-code" title={b.sha256}>SHA-256: {b.sha256.substring(0, 12)}…</span>
                    <small>{new Date(b.createdAt).toLocaleString()}</small>
                  </div>
                </div>
                <div className="backup-actions">
                  <a
                    className="btn-quiet download-link"
                    href={`/api/v1/system/backups/${encodeURIComponent(b.filename)}`}
                    download={b.filename}
                  >
                    下载归档
                  </a>
                  {confirmingRestore === b.filename ? (
                    <div className="confirm-restore-box">
                      <span>确认恢复？</span>
                      <button
                        className="btn-danger-small"
                        disabled={action.busy}
                        onClick={() => void action.run(async () => {
                          const res = await api<RestoreResult>('/system/restore', {
                            method: 'POST',
                            body: { filename: b.filename },
                          })
                          setConfirmingRestore(null)
                          setRestoredMsg(res.message || '系统已在线热恢复完毕')
                          backups.reload()
                        })}
                      >
                        确定
                      </button>
                      <button
                        className="btn-quiet-small"
                        onClick={() => setConfirmingRestore(null)}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button
                      className="btn-quiet danger-hover"
                      disabled={action.busy}
                      onClick={() => setConfirmingRestore(b.filename)}
                      title="在线恢复此快照，未闭环会话将被收敛标记为 INTERRUPTED"
                    >
                      在线恢复
                    </button>
                  )}
                </div>
              </div>
            ))}
            {backups.data?.length === 0 && (
              <p className="list-empty">暂无历史备份快照。点击上方按钮可立即生成一份全量数据热备。</p>
            )}
          </div>
        </section>

        {/* Subagent 协同网络 */}
        <section className="settings-card">
          <div className="card-header">
            <div>
              <h2>子智能体协同网络 (Subagents)</h2>
              <small>主 Agent 派发子任务，分配独立受限工作区，支持生命周期监控与级联取消</small>
            </div>
          </div>

          <div className="subagent-tasks-list">
            {subagentTasks.data?.map(t => (
              <div className="subagent-card" key={t.taskId}>
                <div className="subagent-header">
                  <div className="task-badge-row">
                    <span className={`status-pill pill-${t.status.toLowerCase()}`}>
                      {t.status === 'RUNNING' ? '● 执行中' : t.status === 'COMPLETED' ? '✓ 已完成' : t.status === 'CANCELLED' ? '✕ 已取消' : t.status}
                    </span>
                    <code>任务ID: {t.taskId}</code>
                    <small>助手: {t.assistantId}</small>
                  </div>
                  {(t.status === 'RUNNING' || t.status === 'PENDING') && (
                    <button
                      className="btn-danger-quiet"
                      disabled={action.busy}
                      onClick={() => void action.run(async () => {
                        await api(`/orchestration/subagents/${t.taskId}/cancel`, { method: 'POST' })
                        subagentTasks.reload()
                      })}
                    >
                      取消任务
                    </button>
                  )}
                </div>
                <p className="task-instruction"><strong>指令：</strong>{t.instruction}</p>
                {t.result ? (
                  <div className="task-result">
                    <small>执行产出：</small>
                    <pre>{t.result}</pre>
                  </div>
                ) : null}
              </div>
            ))}
            {subagentTasks.data?.length === 0 && (
              <p className="list-empty">当前没有进行中的 Subagent 派发任务。在复杂长任务中，主 Agent 会自主派发子任务。</p>
            )}
          </div>
        </section>

        {/* 外部通道与 Webhook 守则 */}
        <section className="settings-card full-span">
          <div className="card-header">
            <div>
              <h2>外部通道与触发器接入 (External Triggers)</h2>
              <small>坚守 DeepSeek「默认 Observe 隔离防线」，外部事件统一作为待审阅事项入库，杜绝未授权自动副作用</small>
            </div>
          </div>

          <div className="webhooks-guide-grid">
            <div className="webhook-endpoint-box">
              <h3>GitHub Webhook</h3>
              <code>POST /api/v1/orchestration/webhooks/github</code>
              <p>支持 <code>X-Hub-Signature-256</code> HMAC 签名防篡改验证。</p>
            </div>
            <div className="webhook-endpoint-box">
              <h3>Slack Webhook</h3>
              <code>POST /api/v1/orchestration/webhooks/slack</code>
              <p>支持 <code>v0</code> 签名与 5 分钟时钟偏移防重放攻击防护。</p>
            </div>
            <div className="webhook-endpoint-box">
              <h3>飞书 / Lark Webhook</h3>
              <code>POST /api/v1/orchestration/webhooks/feishu</code>
              <p>支持 <code>X-Lark-Signature</code> 签名校验与 URL Challenge 验证。</p>
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}
