export async function api<T>(path: string, options: { method?: string; body?: unknown; key?: string; signal?: AbortSignal } = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method: options.method ?? 'GET', credentials: 'same-origin', signal: options.signal,
    headers: { ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(options.method && options.method !== 'GET' ? { 'Idempotency-Key': options.key ?? crypto.randomUUID() } : {}) },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as { detail?: string; code?: string }
    throw new Error(problem.detail ?? problem.code ?? `请求失败 (${response.status})`)
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}
export type Assistant = { id: string; name: string; providerId: string; model: string; toolPermissionMode: string; approvalPolicy: string }
export type Content = { type: string; text?: string; toolName?: string; callId?: string; content?: Content[]; isError?: boolean }
export type ChatMessage = { id: string; role: string; content: Content[]; status?: string }
export type Approval = { id: string; toolName: string; reason: string; argumentsJson: string; state: string; requestedDecision: string | null }
export type Conversation = { id: string; title: string; sessionId: string; assistantId: string; status: string }
export type Evaluation = { id: string; triggerId: string; decision: string; recommendation: string | null; reason: string; createdAt: string }
export type Ticket = { id: string; title: string; note: string; status: string; mandateId: string; createdAt: string }
export type Assignment = { id: string; ticketId: string; status: string; report: string; note: string; createdAt: string }
export type Snapshot = { evals: Record<string, Evaluation>; triggers: Record<string, { text: string; source: string }>;
  tickets: Record<string, Ticket>; mandates: Record<string, { revoked: boolean; expiresAt: string; version: number; assistantId: string }>;
  assignments: Record<string, Assignment> }
export const labels: Record<string, string> = { OPEN: '执行中', WAITING: '需要处理', READY: '待验收', ENDED: '已结束',
  PENDING: '等待执行', RUNNING: '正在执行', PROGRESS: '执行中', BLOCKED: '已阻塞', FAILED: '失败', DROPPED: '已停止', OBSERVE: '等待决定' }
export function blockText(block: Content): string {
  if (block.text !== undefined) return block.text
  if (block.content) return block.content.map(blockText).join('\n')
  if (block.toolName) return `调用工具：${block.toolName}`
  return `[${block.type}]`
}
