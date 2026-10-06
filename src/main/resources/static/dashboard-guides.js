export const guides = [
  { scenario: 'COLD_WARM', title: 'Cold read, warm cache', button: 'Run cold / warm', buyers: 2, concurrency: 1,
    setup: 'Two sequential GETs against a new fixture.',
    expected: 'Inspect DATABASE then REDIS_CACHE, the actual flow and cache-write outcome. Invalidation, expiry or recovery can prevent a hit.' },
  { scenario: 'STAMPEDE', title: 'One cold key, many readers', button: 'Run stampede comparison', buyers: 50, concurrency: 10,
    setup: 'Independent cold fixtures with protection on, then off.',
    expected: 'Compare actual USER_READ SQL counts, not just misses. General scheduling and bounded lease/wait limits do not guarantee an exact hit/wait distribution.' },
  { scenario: 'COMPARE', title: '50 buyers. 10 units. Five strategies.', button: 'Run guided comparison', buyers: 50, concurrency: 10,
    setup: 'Five sequential independent cases, fresh identities and stock 10 per case.',
    expected: 'Protected cases must conserve inventory after drain. NONE is deliberately unsafe even when this sample passes; retries and buyer completion are separate from safety.' },
  { scenario: 'STALE_FILL', title: 'Old reader, committed writer', button: 'Run stale-fill race', buyers: 1, concurrency: 1,
    setup: 'Injected bounded pause after the real database load; a real PATCH commits before that old reader resumes.',
    expected: 'Inspect REJECTED_GENERATION and the following fresh read. This tests publication fencing, not linearizable cache reads. Requires healthy Redis.' },
  { scenario: 'OUTAGE', title: 'Redis stops. What still works?', button: 'Observe Redis outage', buyers: 1, concurrency: 1,
    setup: 'Stop only this project Redis in a terminal before starting; start it again while the run waits (up to 60 seconds). No web shell control.',
    expected: 'Observe DATABASE_FALLBACK, SQL purchase versus unavailable Redis admission, then readiness and same-key recovery. Healthy Redis is reported honestly, not labeled an outage.' },
  { scenario: 'LOST_RESPONSE', title: 'Receipt lost. Purchase repeated?', button: 'Run lost-response replay', buyers: 1, concurrency: 1,
    setup: 'Injected client-side discard after a real purchase response, then the same key/payload is retried.',
    expected: 'The retry replays the committed receipt. One unique sale, not two decrements. This is not a real network outage or an exactly-once delivery promise.' }
];

export function guideParameters(guide) {
  return { scenario: guide.scenario, strategy: 'ATOMIC_SQL', buyers: guide.buyers, concurrency: guide.concurrency,
    stock: 10, quantity: 1, seed: 1, jitterMs: 0, durationSeconds: 60, stampedeProtection: true };
}

export function observationText(run) {
  const guide = guides.find(item => item.scenario === run.parameters.scenario);
  if (!guide) return '';
  const observed = run.result?.observations;
  const evidence = [];
  if (observed) {
    for (const [key, label] of Object.entries({ coldThenWarmObserved: 'Cold then warm observed',
      stalePublicationRejected: 'Stale publication rejected', sameKeyReplayObserved: 'Same-key replay observed',
      redisCallUnavailableInitially: 'Real Redis initially unavailable', recoveryObserved: 'Recovery observed' })) {
      if (Object.hasOwn(observed, key)) evidence.push(`${label}: ${observed[key]}.`);
    }
    for (const key of ['fault', 'demonstration', 'unsafeMeaning']) if (observed[key]) evidence.push(observed[key]);
    if (observed.freshRead?.data) evidence.push(`Fresh read: ${observed.freshRead.data.name} (version ${observed.freshRead.data.version}).`);
  }
  return `${guide.setup} ${guide.expected} ${evidence.length ? `Recorded observations: ${evidence.join(' ')}` : 'Read the actual case measurements and request details below; no expected outcome is substituted for evidence.'}`;
}
