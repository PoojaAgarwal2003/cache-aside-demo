import { setTimeout as pause } from 'node:timers/promises';

export function localClient(port) {
  if (!/^\d+$/.test(String(port)) || Number(port) < 1024 || Number(port) > 65535) {
    throw new Error('APP_PORT must be an integer from 1024 through 65535.');
  }
  const origin = `http://127.0.0.1:${Number(port)}`;
  return async (path, body) => {
    if (!/^\/(?:status|demo\/runs(?:\/[0-9a-f-]{36}(?:\/(?:export|cancel|events))?)?)$/.test(path)) {
      throw new Error('Only fixed local status/run endpoints are permitted.');
    }
    const response = await fetch(origin + path, {
      method: body === undefined ? 'GET' : 'POST', redirect: 'error', cache: 'no-store',
      headers: { Origin: origin, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8_000)
    });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status}: ${(await response.text()).slice(0, 1000)}`);
    return response.json();
  };
}

export async function waitForRun(request, id, { signal, timeoutMs = 180_000 } = {}) {
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    signal?.throwIfAborted();
    const run = await request(`/demo/runs/${id}`);
    if (run.finalizationBlocked) throw new Error(`Run ${id} requires explicit reconciliation; no new load dispatched.`);
    if (!run.active) return request(`/demo/runs/${id}/export`);
    await pause(200, undefined, { signal });
  }
  throw new Error(`Run ${id} exceeded its bounded completion wait.`);
}
