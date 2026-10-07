import { cpus, totalmem, platform, release, arch } from 'node:os';
import { mkdir, writeFile, rename } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { localClient, waitForRun } from './lab-client.mjs';

export function percentileSummary(samples) {
  if (samples.some(value => typeof value !== 'number' || !Number.isFinite(value) || value < 0)) {
    throw new Error('Latency samples must be finite, nonnegative numbers; unknown durations are not zero.');
  }
  const sorted = [...samples].sort((a, b) => a - b);
  return {
    sampleCount: sorted.length,
    definition: 'Nearest rank: sorted[ceil(p*N)-1]; milliseconds. No interpolation.',
    ...Object.fromEntries([50, 95, 99].map(p => [`p${p}`, sorted.length ? sorted[Math.ceil(p / 100 * sorted.length) - 1] : null])),
    warning: sorted.length < 100
      ? 'Fewer than 100 samples: p99 is the maximum; tail estimates are especially weak.'
      : 'Local correlated samples, not independent trials or a service SLA.'
  };
}

function integer(value, name, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${name} must be an integer in ${min}..${max}.`);
  return value;
}

export function options(input = {}) {
  const allowed = ['warmups', 'trials', 'scenario', 'buyers', 'concurrency', 'stock', 'quantity', 'seed', 'out'];
  for (const key of Object.keys(input)) if (!allowed.includes(key)) throw new Error(`Unknown benchmark option: ${key}`);
  const result = { warmups: 2, trials: 5, scenario: 'COMPARE', buyers: 50, concurrency: 10, stock: 10, quantity: 1, seed: 1, ...input };
  integer(result.warmups, 'warmups', 1, 5); integer(result.trials, 'trials', 2, 20);
  integer(result.buyers, 'buyers', 1, 100); integer(result.concurrency, 'concurrency', 1, Math.min(50, result.buyers));
  integer(result.stock, 'stock', 0, 1_000_000); integer(result.quantity, 'quantity', 1, 1000);
  integer(result.seed, 'seed', 0, Number.MAX_SAFE_INTEGER);
  if (!['COMPARE', 'READ', 'COLD_WARM', 'STAMPEDE'].includes(result.scenario)) throw new Error('scenario must be COMPARE, READ, COLD_WARM or STAMPEDE.');
  if (result.scenario === 'COLD_WARM') {
    if ((input.buyers !== undefined && input.buyers !== 2) || (input.concurrency !== undefined && input.concurrency !== 1)) {
      throw new Error('COLD_WARM requires exactly 2 buyers and concurrency 1.');
    }
    result.buyers = 2; result.concurrency = 1;
  }
  result.out ??= join('benchmark-results', `${new Date().toISOString().replaceAll(':', '-')}-${randomUUID()}`);
  if (typeof result.out !== 'string' || !result.out.trim()) throw new Error('out must name a new evidence directory.');
  return result;
}

export function summarize(exports) {
  const groups = new Map();
  for (const data of exports) {
    for (const item of data.run.result.cases) {
      const key = `${item.caseIndex}:${item.label}`;
      if (!groups.has(key)) groups.set(key, { label: item.label, strategy: item.strategy, unsafe: item.unsafeStrategy, trials: [], all: [], success: [] });
      const group = groups.get(key);
      const attempts = data.attempts.filter(a => a.caseIndex === item.caseIndex && ['MEASURED', 'RETRY'].includes(a.phase));
      for (const attempt of attempts) {
        if (attempt.elapsedMs !== null) group.all.push(attempt.elapsedMs);
        if (attempt.state === 'RESPONSE' && attempt.httpStatus >= 200 && attempt.httpStatus < 300 && attempt.elapsedMs !== null) {
          group.success.push(attempt.elapsedMs);
        }
      }
      group.trials.push({
        runId: data.run.runId, inventoryVerdict: item.inventoryVerdict, finalStock: item.finalStock,
        uniqueSales: item.uniqueSales, soldQuantity: item.soldQuantity, conservation: item.conservation,
        http: item.http, databaseWork: data.run.result.caseDatabaseWork?.[String(item.caseIndex)] ?? null,
        runElapsedMs: data.run.result.elapsedMs
      });
    }
  }
  return [...groups.values()].map(({ all, success, ...group }) => ({
    ...group, trialCount: group.trials.length, allAttemptLatency: percentileSummary(all), successLatency: percentileSummary(success)
  }));
}

export async function collectBenchmark(input, request, { signal } = {}) {
  const config = options(input);
  const status = await request('/status');
  if (status.application !== 'FlashSale Lab' || !status.profiles?.includes('benchmark')
      || status.readDelayMs !== 0 || status.purchaseDelayMs !== 0 || !status.mutationsEnabled || status.cache !== 'READY') {
    throw new Error('Requires the ready benchmark profile with both synthetic delays exactly zero. Start scripts/start with profile benchmark.');
  }
  await mkdir(dirname(resolve(config.out)), { recursive: true });
  await mkdir(config.out); // Never overwrite prior evidence or silently merge trials.
  const manifest = {
    schemaVersion: 1, state: 'RUNNING', startedAt: new Date().toISOString(),
    config: { ...config, out: undefined }, server: status,
    host: { platform: platform(), release: release(), architecture: arch(), cpu: cpus()[0]?.model ?? 'unavailable',
      logicalCpus: cpus().length, memoryBytes: totalmem(), node: process.version },
    method: {
      warmups: 'Whole HTTP runs excluded from aggregates; same JVM, fresh cold fixtures and identities on every run.',
      order: 'Sequential trials; COMPARE uses fixed NONE/PESSIMISTIC/OPTIMISTIC/ATOMIC_SQL/REDIS_ASSISTED order. Order/JIT/host-load bias remains.',
      cache: 'Each run starts COLD_NEW_FIXTURE; COLD_WARM observes its own first and second reads. Warmup does not prewarm later fixtures.',
      throughput: 'Retain each backend run-wide denominator; no sum of case throughput, average of percentiles, or speedup claim.',
      limits: 'One run at a time; 60s dispatch plus bounded drain. Small local samples are observations, not service SLAs.'
    },
    runs: [], cases: []
  };
  const save = async () => {
    const path = join(config.out, 'benchmark.json');
    await writeFile(`${path}.tmp`, JSON.stringify(manifest, null, 2) + '\n');
    await rename(`${path}.tmp`, path);
  };
  await save();
  const measured = [];
  let active;
  try {
    for (let index = 0; index < config.warmups + config.trials; index++) {
      signal?.throwIfAborted();
      const phase = index < config.warmups ? 'WARMUP' : 'MEASURED';
      const created = await request('/demo/runs', {
        scenario: config.scenario, buyers: config.buyers, concurrency: config.concurrency,
        stock: config.stock, quantity: config.quantity, seed: config.seed, jitterMs: 0, durationSeconds: 60
      });
      active = created.runId;
      manifest.runs.push({ phase, runId: active, file: `${active}.json`, state: created.state });
      await save();
      const data = await waitForRun(request, active, { signal });
      await writeFile(join(config.out, `${active}.json`), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
      manifest.runs.at(-1).state = data.run.state;
      active = undefined;
      await save();
      if (data.run.environment.readDelayMs !== 0 || data.run.environment.purchaseDelayMs !== 0
          || data.run.state !== 'COMPLETED' || data.run.result.completion !== 'ALL_BUYERS_DISPATCHED'
          || data.run.result.cases.some(item => !item.unsafeStrategy && item.inventoryVerdict !== 'PASS')) {
        throw new Error(`Trial ${data.run.runId} is not a complete zero-delay run with protected invariants intact; raw evidence retained.`);
      }
      if (phase === 'MEASURED') measured.push(data);
      manifest.cases = summarize(measured);
      await save();
    }
    manifest.state = 'COMPLETED';
  } catch (error) {
    manifest.state = 'FAILED'; manifest.error = error.message;
    if (active) {
      try {
        await request(`/demo/runs/${active}/cancel`, {});
        const data = await waitForRun(request, active, { timeoutMs: 45_000 });
        await writeFile(join(config.out, `${active}.json`), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
        manifest.runs.at(-1).state = data.run.state;
      } catch (cleanup) {
        manifest.cleanupError = `${cleanup.message}; inspect/reconcile run ${active} before starting more work.`;
      }
    }
    throw error;
  } finally {
    manifest.endedAt = new Date().toISOString();
    await save();
  }
  return manifest;
}

function parseArguments(args) {
  if (args.length % 2) throw new Error('Use --option value pairs; see docs/benchmarks.md.');
  const input = {};
  for (let index = 0; index < args.length; index += 2) {
    if (!args[index].startsWith('--')) throw new Error('Expected a --named option.');
    const name = args[index].slice(2);
    if (name in input) throw new Error(`Duplicate option: ${name}`);
    input[name] = ['scenario', 'out'].includes(name) ? args[index + 1] : Number(args[index + 1]);
  }
  return input;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const abort = new AbortController();
  const stop = () => abort.abort(new Error('Benchmark interrupted; cancelling and draining the current owned run.'));
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  try {
    const config = options(parseArguments(process.argv.slice(2)));
    const report = await collectBenchmark(config, localClient(process.env.APP_PORT ?? '8080'), { signal: abort.signal });
    console.log(`${report.state}: ${report.config.warmups} warmups excluded, ${report.config.trials} measured trials; ${join(config.out, 'benchmark.json')}`);
  } catch (error) {
    console.error(error.message); process.exitCode = 1;
  } finally {
    process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
  }
}
