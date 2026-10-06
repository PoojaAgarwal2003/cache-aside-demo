import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { test, expect } from './fixtures.mjs';

test('native Bruno collection asserts the real packaged API', async ({ lab }, testInfo) => {
  test.setTimeout(150_000);
  try {
    const result = await promisify(execFile)(process.execPath, [
      resolve('node_modules/@usebruno/cli/bin/bru.js'), 'run', '--env', 'Local',
      '--env-var', `baseUrl=${lab.url}`, '--sandbox=safe', '--bail', '--noproxy',
      '--reporter-junit', resolve('test-results/bruno.xml')
    ], { cwd: resolve('bruno'), timeout: 130_000, windowsHide: true, maxBuffer: 2_000_000 });
    await testInfo.attach('bruno-cli', { body: result.stdout + result.stderr, contentType: 'text/plain' });
    expect(result.stdout).toContain('18');
  } catch (error) {
    throw new Error(`Bruno collection failed:\n${error.stdout ?? ''}\n${error.stderr ?? ''}`, { cause: error });
  }
});
