import { useResource } from '../hooks'
import { Empty, ErrorNotice } from '../components/Shared'

type Totals = { inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; reasoningTokens: number; costMicros: number; records: number }
type ModelUsage = { provider: string; model: string; inputTokens: number; outputTokens: number; costMicros: number; records: number }
type DayUsage = { day: string; inputTokens: number; outputTokens: number; records: number }
const number = (value: number) => value.toLocaleString('zh-CN')
export function Usage() {
  const totals = useResource<Totals>('/usage', 5000), models = useResource<ModelUsage[]>('/usage/by-model', 5000), days = useResource<DayUsage[]>('/usage/by-day', 5000)
  const recent = (days.data ?? []).slice(0, 7).reverse(), maximum = Math.max(1, ...recent.map(d => d.inputTokens + d.outputTokens))
  return <div className="page usage-page"><div className="page-heading"><div><h1>用量</h1><p>来自持久用量记录，包含对话、摘要和 Worker 的实际消耗。</p></div><span className="pill">自动更新</span></div>
    <ErrorNotice message={totals.error || models.error || days.error} />
    {totals.data ? <><div className="usage-totals"><section><span>输入 tokens</span><strong>{number(totals.data.inputTokens)}</strong></section><section><span>输出 tokens</span><strong>{number(totals.data.outputTokens)}</strong></section><section><span>计量记录</span><strong>{number(totals.data.records)}</strong></section></div>
      <p className="muted">缓存读取 {number(totals.data.cacheReadTokens)} · 缓存写入 {number(totals.data.cacheWriteTokens)} · 推理 {number(totals.data.reasoningTokens)}</p></> : <p role="status">正在读取用量…</p>}
    <section className="usage-chart"><h2>最近有记录的 7 天</h2>{recent.length ? <div className="chart-bars" role="img" aria-label={recent.map(d => `${d.day}: ${d.inputTokens + d.outputTokens} tokens`).join('；')}>{recent.map(d => <div className="chart-column" key={d.day}><span>{number(d.inputTokens + d.outputTokens)}</span><div className="bar-track"><div style={{ height: `${(d.inputTokens + d.outputTokens) / maximum * 100}%` }} /></div><small>{d.day.slice(5)}</small></div>)}</div> : <Empty title="尚无用量记录">完成一次对话或任务后，这里会显示实际消耗。</Empty>}</section>
    <section><h2>按模型查看</h2><div className="table-scroll"><table><thead><tr><th>模型</th><th>Provider</th><th>输入</th><th>输出</th><th>记录数</th></tr></thead><tbody>{models.data?.map(m => <tr key={`${m.provider}:${m.model}`}><td>{m.model}</td><td>{m.provider}</td><td>{number(m.inputTokens)}</td><td>{number(m.outputTokens)}</td><td>{number(m.records)}</td></tr>)}</tbody></table></div><p className="muted">未配置模型价格时不估算账单金额；最终费用以服务商记录为准。</p></section>
  </div>
}
