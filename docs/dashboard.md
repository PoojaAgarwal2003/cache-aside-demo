# Local dashboard

Open `http://127.0.0.1:8080/` after `scripts\start.ps1` (or the configured
`APP_PORT`). HTML, CSS, JavaScript and the icon ship inside the executable JAR.
No frontend build, CDN, analytics or runtime Node service is required.

The dashboard calls the same `/demo/runs` API as the shell clients. It starts
bounded, isolated real-HTTP experiments; it never derives a sale from a visual
effect. Read/purchase synthetic delays are visible startup settings, not a
browser-side timing control. Dispatch jitter is an independently labeled
per-run setting. Browser seeds are restricted to JavaScript safe integers;
the API and shell clients retain signed 64-bit seed support.

Live inventory and HTTP/SQL counters are explicitly unquiesced. Only the
persisted final result contains the ledger verification verdict. Watch safety,
completion, rejected buyers and HTTP errors separately. NONE remains unsafe
even if a particular sample happens to pass.

Polling is sequential, at most one cycle at a time, with eight-second request
timeouts, bounded failure backoff (up to ten seconds), and suspension while
the page is hidden. Status/run polling does not consume product rate limits.
Observer queries are not counted as experiment SQL work. Recent history is
limited to 20 runs; the event view holds 200 events, deduplicated by cursor,
with explicit backend/local eviction warnings. Detail/export fetches retain
the backend's bounded attempt evidence. No automatic replay occurs on failure.

The selected run ID is in the URL. Refresh, browser history and app restart
read the same persisted record. Export saves actual JSON, including request,
run and purchase identities. A terminal export's first event page is not a
claim that all retained pages fit in the export; use cursor pagination.
Interrupted runs retain their label, and unavailable pre-crash timings stay
unknown. Retry finalization only after restoring dependencies; it does not
dispatch purchases.

Keyboard focus, labels, text warnings, narrow layouts and reduced-motion
preferences are supported. Showcase keeps results large and hides configuration/
request clutter without changing the backend. The default profile renders the
page but disables experiments and diagnostics. Same-origin mutations, loopback
Host checks and a restrictive local-asset CSP remain enforced.

## Six guided views

Cards start documented bounded presets; the form supports customization.
Fixed-shape cold/warm (two sequential readers), stale-fill, outage and
lost-response (one buyer) workloads lock their required buyer/concurrency
fields. Comparison uses five isolated 50-buyer/10-unit fixtures. Stampede uses
two independent fixtures and shows actual SQL counts with protection on/off.
No guide promises a particular general scheduler interleaving.

The active guide explains setup, intended invariants and variability separately
from recorded observations. Stale-fill explicitly labels its injected pause;
lost-response labels an injected discard after real receipt. OUTAGE does not
stop Redis: its expandable terminal instructions use only this project's
Compose Redis service. A healthy-server run says Redis was healthy rather than
inventing an outage. Inspect request details for real source/flow, rejected
publication, replay and purchase identities.

Comparison separates distinct measured buyers from HTTP attempts (which
include retries), and labels the overall run completion state. Throughput is
based on the recorded run-wide measurement interval, not isolated case time.
Use [Bruno](bruno.md) for the sequential API walkthrough.

Milestone 5 covers the real browser failure/accessibility gate. Publishable
screenshots and recorded benchmarks remain milestone 6.

## Browser verification

Node 22+, pinned npm dependencies, JDK 17, and the same real PostgreSQL/Redis
prerequisites as integration tests are required only for development tests:

```powershell
npm.cmd ci
npm.cmd exec -- playwright install chromium
npm.cmd test
.\scripts\verify.ps1 -BrowserOnly
```

The Gradle task builds the actual JAR and a separate test-only fixture owner.
Playwright Chromium visits that packaged process. The owner reuses the existing
Testcontainers/native fixtures and controls only its own processes over stdin;
no fixture-control endpoint is included in the executable JAR.
For the explicit native route, supply `FLASHSALE_TEST_JDBC_URL`,
`FLASHSALE_TEST_REDIS_WSL`, `FLASHSALE_TEST_REDIS_BINARY` as in integration tests.
No dependency failure is turned into a skipped pass. Failure screenshots/traces
are in `test-results/`; the fixture/app log is in the build `browser/` directory.

Coverage includes all guides, authoritative totals/identities/export/refresh,
all-five comparison and unsafe warnings, cancellation during actual dispatch,
real Redis stop/restart, backend loss with measured bounded polling, killed
active app and interrupted recovery, finalization failure/reconciliation,
default-profile denial and a 500-buyer comparison that exceeds backend/local
event buffers. Mobile tests check page-wide overflow, keyboard controls,
scrollable comparison, reduced motion and attach a real screenshot. Every page
fixture rejects foreign-origin runtime requests, CSP violations and unhandled
browser errors. Chromium is the supported automated browser, not a claim of
cross-browser certification. The separate CI job retains browser reports.
