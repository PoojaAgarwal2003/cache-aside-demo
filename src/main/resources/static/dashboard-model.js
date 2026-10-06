export const EVENT_LIMIT = 200;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const number = (value, digits = 0) => typeof value === 'number' && Number.isFinite(value)
  ? value.toLocaleString('en-US', { maximumFractionDigits: digits }) : '--';
export const sum = (rows, field) => rows.reduce((total, row) => total + (typeof row[field] === 'number' ? row[field] : 0), 0);
export function sqlWork(value) {
  if (!value || typeof value !== 'object') return null;
  const rows = Object.values(value);
  return { calls: sum(rows, 'reads') + sum(rows, 'writes') + sum(rows, 'control'), ms: sum(rows, 'executeMs') };
}
export function latency(value) {
  if (!value || !value.sampleCount) return 'No latency samples.';
  return `p50 ${number(value.p50, 1)} / p95 ${number(value.p95, 1)} / p99 ${number(value.p99, 1)} ms (n=${number(value.sampleCount)}; nearest rank)`;
}
export function appendEvents(previous, page) {
  const unique = new Map(previous.map(event => [event.sequence, event]));
  for (const event of page.events) unique.set(event.sequence, event);
  const ordered = [...unique.values()].sort((a, b) => a.sequence - b.sequence);
  return { events: ordered.slice(-EVENT_LIMIT), locallyDropped: Math.max(0, ordered.length - EVENT_LIMIT) };
}
export function runWarning(run) {
  const messages = [];
  if (run.active) messages.push('Live, unquiesced observations. No final safety verdict yet.');
  if (run.finalizationBlocked) messages.push('Finalization blocked: restore dependencies, then retry finalization. No purchases will be replayed.');
  if (run.state === 'INTERRUPTED') messages.push('Process interrupted. Committed keys reconciled; purchases were not automatically replayed. Pre-crash counters are unavailable.');
  if (run.state === 'INCONCLUSIVE' || run.state === 'FAILED') messages.push('This run did not complete conclusively. Do not treat partial evidence as PASS.');
  if (run.parameters.strategy === 'NONE' || run.parameters.scenario === 'COMPARE') messages.push('NONE is deliberately unsafe, even when one sample reports PASS.');
  if ((run.result?.cases ?? run.liveInventory ?? []).some(row => (row.finalStock ?? row.stock) < 0)) messages.push('NEGATIVE DATABASE STOCK: this sample oversold.');
  return messages.join(' ');
}
