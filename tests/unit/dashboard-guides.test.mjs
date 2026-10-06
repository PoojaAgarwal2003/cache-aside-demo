import test from 'node:test';
import assert from 'node:assert/strict';
import { guides, guideParameters, observationText } from '../../src/main/resources/static/dashboard-guides.js';

test('all six presets have bounded compatible shapes and no synthetic results', () => {
  assert.equal(guides.length, 6);
  for (const guide of guides) {
    const preset = guideParameters(guide);
    assert.ok(preset.concurrency <= preset.buyers);
    assert.equal(preset.stock, 10);
    if (['STALE_FILL', 'OUTAGE', 'LOST_RESPONSE'].includes(preset.scenario)) assert.equal(preset.buyers, 1);
    assert.equal(Object.hasOwn(preset, 'result'), false);
  }
});

test('observations report negative findings, not the intended success', () => {
  const run = { parameters: { scenario: 'COLD_WARM' }, result: { observations: { coldThenWarmObserved: false } } };
  assert.match(observationText(run), /Cold then warm observed: false/);
  assert.doesNotMatch(observationText({ ...run, result: null }), /observed: true/);
});
