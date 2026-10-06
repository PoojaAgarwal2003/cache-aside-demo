import { test as base, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';

async function host() {
  const java = process.env.FLASHSALE_BROWSER_JAVA;
  const args = process.env.FLASHSALE_BROWSER_HOST_ARGS;
  if (!java || !args) throw new Error('Run browser tests through gradlew browserTest, which owns the real app/database/Redis fixtures.');
  await mkdir('test-results', { recursive: true });
  const log = createWriteStream('test-results/browser-host.log');
  const child = spawn(java, [`@${args}`], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  const pending = new Map();
  let next = 1;
  let buffered = '';
  let exited = false;
  const wait = id => new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`Browser host operation ${id} timed out; inspect test-results/browser-host.log.`)); }, 100_000);
    pending.set(id, { resolve, reject, timeout });
  });
  const ready = wait(0);
  const exit = new Promise(resolve => {
    child.on('close', (code, signal) => {
      exited = true;
      for (const value of pending.values()) { clearTimeout(value.timeout); value.reject(new Error(`Browser host exited ${code ?? signal}. Inspect test-results/browser-host.log.`)); }
      pending.clear(); log.end(); resolve(code);
    });
  });
  child.on('error', error => {
    for (const value of pending.values()) { clearTimeout(value.timeout); value.reject(error); }
    pending.clear();
  });
  child.stderr.on('data', chunk => log.write(chunk));
  child.stdout.on('data', chunk => {
    log.write(chunk); buffered += chunk.toString();
    let end;
    while ((end = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, end).trim(); buffered = buffered.slice(end + 1);
      if (!line.startsWith('LAB_BROWSER ')) continue;
      const message = JSON.parse(line.slice(12));
      const operation = pending.get(message.id);
      if (operation) {
        clearTimeout(operation.timeout); pending.delete(message.id);
        if (message.error) operation.reject(new Error(message.error)); else operation.resolve(message);
      }
    }
  });
  const command = async command => {
    if (exited) throw new Error('Owned browser test host is no longer running.');
    const id = next++; const response = wait(id);
    child.stdin.write(`${JSON.stringify({ id, command })}\n`);
    return response;
  };
  const finish = async () => {
    child.stdin.end();
    const timeout = setTimeout(() => child.kill(), 90_000);
    try { if (await exit !== 0) throw new Error('Browser host cleanup failed; inspect its log.'); }
    finally { clearTimeout(timeout); }
  };
  try {
    const { url } = await ready;
    return { url, command, async close() {
      try { if (!exited) await command('CLOSE'); }
      finally { await finish(); }
    } };
  } catch (error) {
    try { await finish(); } catch (cleanup) { throw new AggregateError([error, cleanup], 'Browser host startup and cleanup failed.'); }
    throw error;
  }
}

export const test = base.extend({
  lab: [async ({}, use) => {
    const owned = await host();
    try { await use(owned); } finally { await owned.close(); }
  }, { scope: 'worker', timeout: 150_000 }],
  page: async ({ page, lab }, use) => {
    const errors = [];
    const foreign = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (/content.security.policy|violates.*policy/i.test(message.text())) errors.push(message.text()); });
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.protocol.startsWith('http') && url.origin !== lab.url) foreign.push(request.url());
    });
    await page.goto(lab.url);
    await use(page);
    expect(errors, 'No unhandled browser errors').toEqual([]);
    expect(foreign, 'Runtime requests stay on the owned same-origin app').toEqual([]);
  }
});
export { expect };

export async function startRun(page, label = 'Run experiment') {
  const button = page.getByRole('button', { name: label, exact: true });
  await expect(button).toBeEnabled();
  const accepted = page.waitForResponse(response => response.url().endsWith('/demo/runs') && response.request().method() === 'POST');
  await button.click();
  const response = await accepted;
  expect(response.status()).toBe(202);
  const run = await response.json();
  await expect(page).toHaveURL(new RegExp(run.runId));
  return run.runId;
}

export async function exportRun(page, id) {
  const response = await page.request.get(new URL(`/demo/runs/${id}/export`, page.url()).href);
  expect(response.ok()).toBe(true);
  return response.json();
}
