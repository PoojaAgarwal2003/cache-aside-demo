import { appendEvents, latency, number, runWarning, sqlWork, sum, UUID } from './dashboard-model.js';
import { guides, guideParameters, observationText } from './dashboard-guides.js';

const $ = id => document.getElementById(id);
const text = (id, value) => { if ($(id).textContent !== String(value)) $(id).textContent = value; };
const notice = (id, message) => { text(id, message); $(id).hidden = !message; };
const json = value => JSON.stringify(value, null, 2);
const activeStates = new Set(['STARTING', 'RUNNING', 'DRAINING']);
const state = { id: null, run: null, recent: [], events: [], cursor: 0, dropped: 0, attempts: [],
  epoch: 0, online: false, demo: false, busy: false, polling: false, failures: 0, historyKey: '', casesKey: '', timer: null };

async function api(path, method = 'GET', body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(path, { method, signal: controller.signal, credentials: 'same-origin',
      cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    const value = await response.json();
    if (!response.ok) {
      const error = new Error(`${value.code ?? `HTTP ${response.status}`}: ${value.message ?? 'Request failed'}${value.requestId ? ` (request ${value.requestId})` : ''}`);
      error.status = response.status;
      throw error;
    }
    return value;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('Local request exceeded 8 seconds. The backend run may still be active; inspect it before retrying.');
    throw error;
  } finally { clearTimeout(timeout); }
}

function controls() {
  const active = state.run?.active || state.recent.some(run => activeStates.has(run.state));
  $('parameters').disabled = !state.online || !state.demo || state.busy || active;
  $('cancel').disabled = !state.online || !state.demo || state.busy || !state.run?.active;
  $('reconcile').hidden = !state.run?.finalizationBlocked;
  $('reconcile').disabled = !state.online || !state.demo || state.busy;
  $('export').disabled = !state.online || !state.demo || !state.run || state.busy;
  $('load-requests').disabled = !state.online || !state.demo || !state.run || state.busy;
  for (const button of document.querySelectorAll('[data-scenario]')) button.disabled = $('parameters').disabled;
}

function selectRun(id, updateUrl = true) {
  if (!UUID.test(id)) { notice('action-error', 'Invalid run identifier. Choose a persisted run below.'); return; }
  state.id = id; state.run = null; state.epoch++; state.cursor = 0; state.events = []; state.dropped = 0;
  state.attempts = []; state.casesKey = ''; state.historyKey = '';
  $('events').replaceChildren(); $('request-select').replaceChildren(); $('request-select').disabled = true;
  text('event-count', '0 retained here'); notice('event-gap', ''); notice('action-error', ''); notice('run-warning', '');
  notice('scenario-explanation', '');
  text('run-title', 'Loading persisted experiment'); text('run-state', 'Loading authoritative snapshot...');
  text('run-identity', id); text('request-flow', 'Select an HTTP event or load request details.');
  text('request-details', 'No request selected.'); text('result-json', 'Loading...');
  for (const key of ['sales', 'stock', 'replays', 'rejections', 'retries', 'hits', 'db-work', 'attempts']) text(key, '--');
  text('all-latency', 'No latency samples.'); text('success-latency', 'No latency samples.'); text('harness-time', 'Loading...');
  $('comparison-panel').hidden = true;
  if (updateUrl) { const url = new URL(location.href); url.searchParams.set('run', id); history.pushState(null, '', url); }
  controls(); schedule(0);
}

function renderRun(run) {
  state.run = run;
  const result = run.result;
  const rows = result?.cases ?? run.liveInventory;
  const http = result?.http ?? run.liveHttp;
  const database = sqlWork(result?.databaseWork ?? run.liveDatabaseWork);
  text('run-title', `${run.parameters.scenario} experiment`);
  text('run-identity', `runId ${run.runId} | ${run.createdAt} | read delay ${run.environment.readDelayMs}ms / purchase delay ${run.environment.purchaseDelayMs}ms`);
  text('run-state', `${run.state}${result ? ` / inventory ${result.invariantVerdict ?? 'INCONCLUSIVE'}` : ' / awaiting final ledger verification'}`);
  text('run-meaning', run.active ? 'Live SQL observations are not an atomic final verdict. Cancellation seals dispatch, then drains accepted work.'
    : `${result?.completion ?? 'Completion unavailable'}; ${result?.verificationMeaning ?? run.error ?? 'See persisted evidence below.'}`);
  notice('run-warning', runWarning(run));
  notice('scenario-explanation', observationText(run));
  text('sales', number(rows ? sum(rows, 'uniqueSales') : null));
  text('quantity-total', rows ? `${number(sum(rows, 'soldQuantity'))} committed units (quantity matters)` : 'No ledger totals available');
  const stock = rows ? sum(rows, result?.cases ? 'finalStock' : 'stock') : null;
  const negative = rows?.some(row => (row.finalStock ?? row.stock) < 0);
  text('stock', number(stock)); $('stock').classList.toggle('negative', Boolean(negative));
  text('stock-meaning', negative ? 'WARNING: at least one fixture has negative stock' : 'Across selected run fixtures');
  text('replays', number(http?.replays)); text('rejections', number(http?.businessRejections));
  text('retries', number(result?.transactionRetries ?? (rows ? sum(rows, 'transactionRetries') : null)));
  text('hits', number(http ? (http.terminalReadSources?.REDIS_CACHE ?? 0) + (http.terminalReadSources?.REDIS_CACHE_AFTER_WAIT ?? 0) : null));
  text('db-work', number(database?.calls)); text('db-time', database ? `${number(database.ms, 1)}ms JDBC execution; excludes synthetic delay` : 'In-memory work counters unavailable');
  text('attempts', number(http?.attempts));
  text('errors', http ? `${number(http.responses)} responses / ${number(http.errorsOrUnknown)} errors or unknown` : 'Measured requests, not sales');
  text('all-latency', latency(http?.allAttemptLatency)); text('success-latency', latency(http?.successLatency));
  text('harness-time', `${number(result?.elapsedMs ?? run.liveElapsedMs, 1)}ms elapsed; ${number(http?.httpAttemptsPerSecond, 2)} measured HTTP/s. Includes setup/drain, not service capacity.`);
  text('result-json', json({ parameters: run.parameters, environment: run.environment, finalResult: result,
    ...(run.active ? { liveDatabaseWork: run.liveDatabaseWork, liveInventory: run.liveInventory } : {}) }));
  renderCases(run); controls();
}

function renderCases(run) {
  const cases = run.result?.cases;
  $('comparison-panel').hidden = !cases?.length;
  const key = json(cases);
  if (!cases || state.casesKey === key) return;
  state.casesKey = key;
  const body = document.createDocumentFragment();
  for (const entry of cases) {
    const tr = document.createElement('tr');
    const work = sqlWork(run.result.caseDatabaseWork?.[entry.caseIndex]);
    const values = [`${entry.label} / ${entry.strategy}${entry.unsafeStrategy ? ' (UNSAFE)' : ''}`, entry.inventoryVerdict,
      `${number(entry.http.buyersDispatched)} / ${number(run.parameters.buyers)} buyers dispatched; ${number(entry.http.attempts)} attempts; run ${run.state}`,
      `${number(entry.uniqueSales)} / ${number(entry.soldQuantity)}`, number(entry.finalStock),
      number(entry.http.errorsOrUnknown), number(work?.calls), `${number(entry.http.measurementWindowMs, 1)}ms`,
      number(entry.http.httpAttemptsPerSecond, 2), latency(entry.http.allAttemptLatency), latency(entry.http.successLatency)];
    for (const [index, value] of values.entries()) {
      const cell = document.createElement(index === 0 ? 'th' : 'td');
      if (index === 0) cell.scope = 'row';
      cell.textContent = value; tr.append(cell);
    }
    body.append(tr);
  }
  $('comparison-body').replaceChildren(body);
}

function renderHistory(runs) {
  const key = json([runs, state.id]);
  if (key === state.historyKey) return;
  state.historyKey = key;
  const list = document.createDocumentFragment();
  for (const run of runs) {
    const li = document.createElement('li'); const button = document.createElement('button');
    button.type = 'button'; button.textContent = `${run.state} | ${run.createdAt} | ${run.runId}`;
    if (run.runId === state.id) button.setAttribute('aria-current', 'true');
    button.addEventListener('click', () => selectRun(run.runId));
    li.append(button); list.append(li);
  }
  if (!runs.length) { const li = document.createElement('li'); li.textContent = 'No persisted runs yet.'; list.append(li); }
  $('history').replaceChildren(list);
}

function renderEvents(page) {
  const added = appendEvents(state.events, page);
  state.cursor = page.nextCursor;
  state.dropped += added.locallyDropped;
  const changed = json(added.events) !== json(state.events);
  state.events = added.events;
  notice('event-gap', page.gap || page.truncated || state.dropped
    ? `Event history is incomplete: ${page.droppedEvents} evicted by backend retention; ${state.dropped} evicted from this view. Final totals and exported attempts remain authoritative.` : '');
  text('event-count', `${state.events.length} / 200 retained here${page.hasMore ? ' (catching up)' : ''}`);
  if (!changed) return;
  const list = document.createDocumentFragment();
  for (const event of state.events) {
    const li = document.createElement('li');
    const title = `${event.sequence} | ${event.at} | ${event.kind}`;
    if (event.kind === 'HTTP_RESULT') {
      const button = document.createElement('button'); button.type = 'button';
      button.textContent = `${title} | attempt ${event.detail.attemptId}: ${event.detail.state}`;
      button.addEventListener('click', () => action(() => loadRequests(event.detail.attemptId)));
      li.append(button);
    } else { li.textContent = title; }
    list.append(li);
  }
  $('events').replaceChildren(list);
}

async function loadRequests(attemptId) {
  const id = state.id; const epoch = state.epoch;
  const exported = await api(`/demo/runs/${id}/export`);
  if (epoch !== state.epoch) return;
  state.attempts = exported.attempts;
  const options = document.createDocumentFragment();
  for (const attempt of state.attempts) {
    const option = document.createElement('option'); option.value = attempt.attemptId;
    option.textContent = `${attempt.attemptId} | case ${attempt.caseIndex} buyer ${attempt.buyer} | ${attempt.method} | ${attempt.state} ${attempt.httpStatus ?? ''}`;
    options.append(option);
  }
  $('request-select').replaceChildren(options); $('request-select').disabled = !state.attempts.length;
  if (attemptId) $('request-select').value = String(attemptId);
  renderRequest();
}

function renderRequest() {
  const attempt = state.attempts.find(item => String(item.attemptId) === $('request-select').value);
  if (!attempt) { text('request-details', 'No persisted request available. Refresh request details after dispatch.'); return; }
  const body = attempt.response?.body;
  const flow = body?.flow?.join(' -> ') ?? (body?.code ? `Database purchase outcome: ${body.code}` : 'Unknown or deliberately discarded HTTP outcome; consult reconciled keys.');
  text('request-flow', `Buyer ${attempt.buyer} -> ${flow}${body?.source ? ` | source ${body.source}` : ''}${body?.cacheWriteOutcome ? ` | cache write ${body.cacheWriteOutcome}` : ''}`);
  text('request-details', json({ runId: state.id, ...attempt }));
}

function schedule(delay = 1000) {
  clearTimeout(state.timer);
  if (!document.hidden) state.timer = setTimeout(poll, delay);
}

async function poll() {
  if (state.polling) return;
  state.polling = true;
  const epoch = state.epoch;
  try {
    const status = await api('/status');
    state.demo = status.mutationsEnabled; state.online = true;
    text('health', `PostgreSQL ${status.database} / product cache ${status.cache}`);
    text('delays', `Synthetic work: read ${status.readDelayMs}ms / purchase ${status.purchaseDelayMs}ms. Not database latency.`);
    $('default-notice').hidden = state.demo;
    if (state.demo) {
      const [recent, diagnostics] = await Promise.all([api('/demo/runs'), api('/cache/status')]);
      state.recent = recent; renderHistory(recent); text('diagnostics', json(diagnostics));
      if (!state.id && recent.length) { selectRun(recent[0].runId); return; }
      if (state.id && epoch === state.epoch) {
        let run;
        try { run = await api(`/demo/runs/${state.id}`); }
        catch (error) {
          if (error.status !== 404) throw error;
          if (epoch === state.epoch) {
            notice('action-error', error.message);
            text('run-state', 'Run not found. Choose a persisted run or start another.');
          }
        }
        if (epoch !== state.epoch) return;
        if (run) {
          renderRun(run);
          const page = await api(`/demo/runs/${state.id}/events?after=${state.cursor}&limit=100`);
          if (epoch === state.epoch) renderEvents(page);
        }
      }
    } else {
      text('run-state', 'Run reporting is disabled in this profile. Previous evidence is not refreshed.');
    }
    state.failures = 0; notice('connection-error', '');
  } catch (error) {
    state.online = false; state.failures++;
    text('health', 'Connection unavailable / last observations may be stale');
    notice('connection-error', `${error.message} Last displayed results are not fresh. Automatic retry is bounded; use Refresh connection and runs to retry now.`);
  } finally {
    state.polling = false; controls();
    schedule(epoch !== state.epoch ? 0 : state.failures ? Math.min(10000, 1000 * 2 ** Math.min(state.failures, 4)) : 1000);
  }
}

async function action(work) {
  if (state.busy) return;
  state.busy = true; notice('action-error', ''); controls();
  try { await work(); }
  catch (error) { notice('action-error', error.message); }
  finally { state.busy = false; controls(); schedule(0); }
}

function parameters() {
  const form = new FormData($('run-form'));
  const value = {};
  for (const [key, raw] of form) {
    if (key === 'scenario' || key === 'strategy') value[key] = raw;
    else if (key !== 'stampedeProtection') {
      const integer = Number(raw);
      if (!Number.isSafeInteger(integer)) throw new Error(`${key} must be a safe whole number.`);
      value[key] = integer;
    }
  }
  value.stampedeProtection = form.has('stampedeProtection');
  if (value.concurrency > value.buyers) throw new Error('Concurrency cannot exceed buyers.');
  return value;
}

async function start(parameters) {
  const run = await api('/demo/runs', 'POST', parameters);
  selectRun(run.runId); renderRun(run);
  $('run-state').scrollIntoView({ block: 'nearest' });
}

function fixedShape() {
  const guide = guides.find(item => item.scenario === $('scenario').value);
  const fixed = guide && ['COLD_WARM', 'STALE_FILL', 'OUTAGE', 'LOST_RESPONSE'].includes(guide.scenario);
  for (const name of ['buyers', 'concurrency']) {
    const input = $('run-form').elements.namedItem(name);
    input.readOnly = Boolean(fixed);
    if (fixed) input.value = guide[name];
  }
}
for (const guide of guides) {
  const card = document.createElement('article'); card.className = 'guide';
  const heading = document.createElement('h3'); heading.textContent = guide.title;
  const setup = document.createElement('p'); setup.textContent = guide.setup;
  const expected = document.createElement('p'); expected.textContent = guide.expected;
  const button = document.createElement('button'); button.type = 'button'; button.dataset.scenario = guide.scenario;
  button.textContent = guide.button; button.disabled = true;
  button.addEventListener('click', () => {
    const value = guideParameters(guide);
    for (const [key, field] of Object.entries(value)) {
      const input = $('run-form').elements.namedItem(key);
      if (input.type === 'checkbox') input.checked = field; else input.value = field;
    }
    fixedShape(); $('unsafe-warning').hidden = true;
    action(() => start(value));
  });
  card.append(heading, setup, expected, button); $('scenario-cards').append(card);
}
$('scenario').addEventListener('change', fixedShape);
$('run-form').addEventListener('submit', event => {
  event.preventDefault();
  // Read form values before disabling the fieldset for the mutation.
  let value;
  try { value = parameters(); } catch (error) { notice('action-error', error.message); return; }
  action(() => start(value));
});
$('compare').addEventListener('click', () => {
  let value;
  try { value = { ...parameters(), scenario: 'COMPARE' }; } catch (error) { notice('action-error', error.message); return; }
  action(() => start(value));
});
$('strategy').addEventListener('change', () => { $('unsafe-warning').hidden = $('strategy').value !== 'NONE'; });
$('cancel').addEventListener('click', () => action(async () => { await api(`/demo/runs/${state.id}/cancel`, 'POST'); }));
$('reconcile').addEventListener('click', () => action(async () => { await api(`/demo/runs/${state.id}/reconcile`, 'POST'); }));
$('export').addEventListener('click', () => action(async () => {
  const id = state.id; const exported = await api(`/demo/runs/${id}/export`);
  const url = URL.createObjectURL(new Blob([json(exported)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = `flashsale-${id}.json`;
  document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}));
$('load-requests').addEventListener('click', () => action(() => loadRequests()));
$('request-select').addEventListener('change', renderRequest);
$('refresh').addEventListener('click', () => { state.failures = 0; schedule(0); });
$('showcase').addEventListener('click', () => {
  const enabled = document.body.classList.toggle('showcase');
  $('showcase').setAttribute('aria-pressed', String(enabled)); $('showcase').textContent = enabled ? 'Exit showcase' : 'Showcase layout';
});
document.addEventListener('visibilitychange', () => { if (document.hidden) clearTimeout(state.timer); else schedule(0); });
window.addEventListener('popstate', () => {
  const id = new URL(location.href).searchParams.get('run');
  if (id) selectRun(id, false);
  else { location.reload(); }
});
const initial = new URL(location.href).searchParams.get('run');
if (initial) selectRun(initial, false);
schedule(0);
