# Recording the real lab

Suggested hook: **"50 buyers. 10 items. I compared five inventory strategies -
and tested what happens when requests retry and Redis goes down."**

These are recording **scripts**, not a claim that a video was recorded.
Use only actual completed exports/screenshots. Hide unrelated terminals and
personal paths; use the local app, never production data. Keep run ID, delay
disclosure, unsafe warnings and outcome labels visible. Recording a shorter
edit is fine; do not imply that an edited transition is measured elapsed time.

## Clip 1: inventory, 45-60 seconds

Start `.\scripts\start.ps1` (Bash: `./scripts/start.sh demo`) and open the root
URL. Default demo delays are read 1000ms/purchase 100ms; the dashboard displays
the actual settings. Use the guided 50-buyers/10-units comparison. Do a complete
unrecorded rehearsal first, then record a **new** run with independent fixtures.

| Time | Screen action / narration |
|---|---|
| 0-8s | Show stock 10 and buyers 50. "The database decides what was sold; HTTP success counts are not the ledger." |
| 8-20s | Run guided comparison, show live results as unquiesced. "NONE checks then decrements without a stock predicate. It is intentionally unsafe." |
| 20-38s | On actual completion, enter Showcase. Read NONE's observed stock and verdict, not a scripted number. If it did not oversell, explicitly say so; the barrier-controlled acceptance test demonstrates the race reliably. |
| 38-50s | Point to protected cases' sold quantity, final stock and conservation. Explain row lock, version predicate and conditional SQL UPDATE. "Redis only admits; PostgreSQL still commits." |
| 50-60s | Export JSON. Show run ID and request/ledger evidence. "Safety is separate from errors, retries and how many buyers finish. This is one local app, not a production capacity claim." |

Do not repeat a run secretly until it fails and present the selected sample as
typical. General scheduling does not promise overselling every time. To explain
the deterministic unsafe race, show the existing controlled acceptance test
`PurchaseStrategiesAcceptanceTest` rather than adding a hidden traffic barrier
to the normal dashboard.

## Clip 2: cache and outage, 45-60 seconds

Use a separate clip so cache trust is not confused with stock authority.

| Time | Screen action / narration |
|---|---|
| 0-12s | Run cold/warm. Open persisted request details: actual DATABASE then REDIS_CACHE. Explain the displayed synthetic read delay. |
| 12-24s | Run stale-fill race. Call out its **injected bounded pause**, real committed writer, and REJECTED_GENERATION observation. "Fencing blocks old publication after invalidation, not all stale reads." |
| 24-38s | In a visible terminal run `docker compose --project-name flashsale-lab stop redis`, then Observe Redis outage. Show fallback read, atomic sale, admission unavailable and limiter bypass. The stop is real, not an HTTP fault button. |
| 38-52s | After WAITING_FOR_REDIS, run `docker compose --project-name flashsale-lab start redis`. Wait for actual recovery; edit waiting time only with an explicit cut. "Listener health and a new epoch are required; a closed breaker alone is not freshness." |
| 52-60s | Show recovered result/export and limitation: cache is eventual, LISTEN is not durable CDC, fallback remains bounded. |

If cache recovery exceeds the clip duration, show an explicitly labeled cut or
record a longer clip. Never replace failure with a fake successful view.
Stop the app with its owner's normal command afterward; preserve volumes.

## Optional retry/restart segment

Run lost-response replay: label the client-side response discard as injected
after actual receipt. Show one sale, two attempts, same-key replay and the
purchase ID. For actual process death and retained interrupted results, use
the isolated [walkthrough](walkthrough.md); it kills only its owned JVM and
never replays an unfinished purchase automatically.

## Recreate presentation assets

Install pinned development tools and run the browser gate:

```powershell
npm.cmd ci
npm.cmd exec -- playwright install chromium
.\scripts\verify.ps1 -BrowserOnly
```

The presentation test captures the real comparison, cold/warm request view and
390px mobile page into `test-results/presentation/`, with matching JSON exports,
capture timestamps and SHA-256 hashes. It then switches to the true benchmark
profile, runs 2 excluded warmups + 5 measured trials, and exercises the actual
PowerShell 5.1/Bash benchmark wrapper. These zero-delay measurements are separate
from the screenshot's synthetic delays. Browser gate outputs are ignored by
default; the deliberate published examples live under `docs/assets/` and
`docs/evidence/milestone-6/`. No generated argument file or personal path is
published with them.
