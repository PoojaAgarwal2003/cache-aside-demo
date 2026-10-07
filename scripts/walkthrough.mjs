import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdir, open, readFile, writeFile, access } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { localClient } from './lab-client.mjs';
import { exerciseLifecycle } from './walkthrough-scenarios.mjs';
import { collectBenchmark } from './benchmark.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--out')) throw new Error('Usage: node scripts/walkthrough.mjs [--out NEW_DIRECTORY]');
const directory = resolve(args[1] ?? join(root, 'walkthrough-results', randomUUID()));
const project = `flashsale-walkthrough-${randomUUID().slice(0, 8)}`;
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const jar = resolve(root, process.env.FLASHSALE_BUILD_DIR ?? 'build', 'libs', 'cache-aside-demo.jar');
const abort = new AbortController();
const stop = () => abort.abort(new Error('Walkthrough interrupted; stopping only its owned processes/services.'));
process.once('SIGINT', stop); process.once('SIGTERM', stop);
let app, appClosed, log, servicesStarted = false;
let request, env;
const evidence = { project, state: 'STARTING', volumes: 'Preserved; no down -v or volume deletion is performed.' };
const docker = (...command) => execFileSync('docker', command, {
  cwd: root, env, encoding: 'utf8', timeout: 150_000, maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']
});
const compose = (...command) => docker('compose', '--project-name', project, '--project-directory', root,
  '-f', join(root, 'docker-compose.yml'), ...command);
async function ports() {
  const servers = [];
  try {
    for (let i = 0; i < 3; i++) {
      const server = createServer(); servers.push(server);
      await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
    }
    return servers.map(server => server.address().port);
  } finally {
    await Promise.all(servers.filter(server => server.listening).map(server => new Promise((done, reject) =>
      server.close(error => error ? reject(error) : done()))));
  }
}
async function stopApp() {
  if (!app) return;
  if (app.exitCode === null && app.signalCode === null) app.kill('SIGKILL');
  let timer;
  try {
    await Promise.race([appClosed, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Owned JVM did not exit within 15s.')), 15_000);
    })]);
  } finally { clearTimeout(timer); }
  app = undefined;
}
async function startApp(profile) {
  abort.signal.throwIfAborted();
  app = spawn(java, ['-jar', jar, `--spring.profiles.active=${profile}`,
    '--server.address=127.0.0.1', `--server.port=${env.APP_PORT}`,
    `--spring.datasource.url=jdbc:postgresql://127.0.0.1:${env.POSTGRES_PORT}/flashsale_lab?currentSchema=lab&connectTimeout=3&socketTimeout=15`,
    '--spring.datasource.username=flashsale', '--spring.data.redis.host=127.0.0.1', `--spring.data.redis.port=${env.REDIS_PORT}`],
  { cwd: root, env: { ...env, SPRING_DATASOURCE_PASSWORD: env.DB_PASSWORD }, windowsHide: true, stdio: ['ignore', log.fd, log.fd] });
  let spawnError;
  app.once('error', error => { spawnError = error; });
  appClosed = new Promise(done => app.once('close', done));
  const deadline = performance.now() + 90_000;
  let last;
  while (performance.now() < deadline) {
    abort.signal.throwIfAborted();
    if (spawnError) throw spawnError;
    if (app.exitCode !== null || app.signalCode !== null) throw new Error('Owned JVM exited; inspect app.log.');
    try {
      const status = await request('/status');
      if (status.application === 'FlashSale Lab' && status.cache === 'READY' && status.profiles.includes(profile)) return status;
    } catch (error) { last = error.message; }
    await pause(200, undefined, { signal: abort.signal });
  }
  throw new Error(`Owned app did not become ready: ${last ?? 'cache not READY'}`);
}
function redisIdentity(mark) {
  const key = `flashsale:test:${project}`;
  if (mark) compose('exec', '-T', 'redis', 'redis-cli', 'SET', key, randomUUID(), 'EX', '600');
  const info = Object.fromEntries(compose('exec', '-T', 'redis', 'redis-cli', 'INFO', 'server')
    .split(/\r?\n/).filter(line => line.includes(':')).map(line => line.split(':', 2)));
  return { redis: { runId: info.run_id, version: info.redis_version,
    marker: compose('exec', '-T', 'redis', 'redis-cli', 'GET', key).trim() } };
}
async function control(command) {
  switch (command) {
    case 'REDIS_MARK': return redisIdentity(true);
    case 'REDIS_IDENTITY': return redisIdentity(false);
    case 'STOP_REDIS': compose('stop', 'redis'); break;
    case 'START_REDIS': compose('up', '-d', '--wait', '--wait-timeout', '60', 'redis'); break;
    case 'RESTART_APP': await stopApp(); await startApp('demo'); break;
    default: throw new Error(`Unsupported owned lifecycle command: ${command}`);
  }
  return {};
}
try {
  await access(jar);
  docker('info', '--format', '{{.ServerVersion}}');
  await mkdir(dirname(directory), { recursive: true });
  await mkdir(directory);
  const [appPort, postgresPort, redisPort] = await ports();
  env = { ...process.env, APP_PORT: String(appPort), POSTGRES_PORT: String(postgresPort),
    REDIS_PORT: String(redisPort), DB_PASSWORD: process.env.DB_PASSWORD ?? 'local-lab-only' };
  request = localClient(appPort);
  log = await open(join(directory, 'app.log'), 'a');
  evidence.ports = { app: appPort, postgres: postgresPort, redis: redisPort };
  servicesStarted = true;
  compose('up', '-d', '--wait', '--wait-timeout', '90');
  evidence.images = JSON.parse(compose('images', '--format', 'json'));
  evidence.demo = await startApp('demo');
  await exerciseLifecycle(request, control, directory, { signal: abort.signal });

  const comparison = JSON.parse(await readFile(join(directory, 'comparison.json'), 'utf8'));
  const cases = comparison.run.result.cases;
  const ids = cases.map(item => { assert.ok(Number.isSafeInteger(item.productId) && item.productId > 0); return item.productId; });
  const rows = JSON.parse(compose('exec', '-T', 'postgres', 'psql', '-U', 'flashsale', '-d', 'flashsale_lab', '-tAc',
    `SELECT json_agg(json_build_object('productId',p.id,'stock',p.stock,'soldQuantity',(SELECT coalesce(sum(quantity),0) FROM lab.purchase_ledger l WHERE l.product_id=p.id),'uniqueSales',(SELECT count(*) FROM lab.purchase_ledger l WHERE l.product_id=p.id))) FROM lab.products p WHERE p.id IN (${ids.join(',')})`));
  for (const item of cases) {
    const row = rows.find(row => row.productId === item.productId);
    assert.equal(row.stock, item.finalStock); assert.equal(row.soldQuantity, item.soldQuantity); assert.equal(row.uniqueSales, item.uniqueSales);
    assert.equal(row.stock + row.soldQuantity, item.initialStock);
  }
  await writeFile(join(directory, 'direct-ledger.json'), JSON.stringify(rows, null, 2) + '\n');
  await stopApp();
  compose('restart', 'postgres');
  compose('up', '-d', '--wait', '--wait-timeout', '60', 'postgres');
  await startApp('benchmark');
  assert.deepEqual(await request(`/demo/runs/${comparison.run.runId}/export`), comparison);
  evidence.postgresRestartPreservedExport = true;
  await collectBenchmark({ out: join(directory, 'benchmark') }, request, { signal: abort.signal });
  evidence.state = 'COMPLETED';
} catch (error) {
  evidence.state = 'FAILED'; evidence.error = error.message; process.exitCode = 1;
  console.error(error.message);
} finally {
  try { await stopApp(); } catch (error) { evidence.state = 'FAILED'; evidence.appCleanupError = error.message; process.exitCode = 1; }
  try {
    if (servicesStarted) {
      compose('stop');
      assert.equal(compose('ps', '--status', 'running', '--quiet').trim(), '');
      evidence.servicesStopped = true;
    }
  } catch (error) { evidence.state = 'FAILED'; evidence.serviceCleanupError = error.message; process.exitCode = 1; }
  if (log) {
    await log.close();
    await writeFile(join(directory, 'compose.json'), JSON.stringify(evidence, null, 2) + '\n');
  }
  process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
  console.log(`${evidence.state}; Compose project ${project}; evidence: ${directory}; named volumes retained.`);
}
