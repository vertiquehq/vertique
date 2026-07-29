# Plan — flaky async/network test determinism (issues #7 #8 #9 #10 #11 #31 #47)

## 1. Context & goal

Seven open flake reports in `vertiquehq/vertique-dev` share one signature: the test
passes in isolation and on re-run, but fails once under full-reactor load. Every
occurrence erodes CI's gating value, so they are batched as one initiative rather
than seven independent fixes.

The reports assert two root causes — "port/server readiness" and "WireMock stub
race". Pre-flight verification (§2) confirms the WireMock cause and **refutes** the
port/readiness cause. The plan therefore fixes what is proven, and applies the
`testing.md` async-determinism checklist uniformly to the remaining classes as
hardening (explicit user decision, 2026-07-29), on the strict condition that every
change is *subtractive* — it removes a source of nondeterminism and never tightens
an assertion (`testing.md` § Flake-fix discipline).

Why now: the flake family is recurring (#31 on 07-28, #47 on 07-29, both after the
last determinism pass), so the cost is ongoing. Boring alternative considered and
rejected: retry-on-failure in Surefire, which would hide the signal rather than
remove the cause.

## 2. Pre-flight findings (verified 2026-07-29 against `origin/main` @ 9b0a6c6)

**Refuted hypotheses** — verified experimentally, not assumed:

| Hypothesis | Method | Result |
|---|---|---|
| IPv6/dual-stack misroute from `listen(0)` + `"localhost"` | Live Vert.x bind probe | Refuted. `localhost` resolves `::1` first on this host, yet `listen(0)` + `"localhost"` returns HTTP 200 reliably. |
| Server readiness — routes registered after `listen()` | Read `ProfiledBodyParseIT.deploy` | Refuted. `mount.createRouter(vertx)` is awaited via `.compose(...)` *before* `listen(0)`. |
| Ephemeral port reuse → stale pooled connection → misroute | 500-round bind/request/close probe with a class-shared client | Refuted. 500 rounds produced **500 distinct ports, 0 reuse, 0 bad responses**. |
| CPU starvation alone reproduces the family | 12 rounds × 48 tests under 10× CPU load | Did not reproduce (576 executions, 0 failures). |

**Decisive finding.** Commit `a64e4c5` (2026-07-27) already applied `127.0.0.1`
bind/connect pinning **and** lifecycle barriers to `RestRequestCompletionEmitterTest`,
and `git merge-base` confirms that fix was already present in the tree when #11 was
observed. Re-applying "pin the port + add a barrier" to that class would be a
placebo. #11 has **no identified mechanism**; it is hardened, not "fixed".

**Confirmed defects** (all re-verified at the branch base):

- **WireMock per-test lifecycle** — `RestClientAttemptObserverIT`,
  `RestClientConnectionFailureIT`, `RestClientResponseFailureIT` each start/stop a
  `WireMockServer` in `@BeforeEach`. This is the documented stub-registration race
  (`testing.md` § WireMock Lifecycle) with the documented signature (mass 404s).
  Two of the three are **not** ticketed — found by sweeping the module.
  `RestClientRetryIT` already uses `@RegisterExtension` and is the in-module reference.
- **Per-test `HttpClient`** — `CorrelationIngressMiddlewareTest.startServer` allocates
  a fresh client per test method (`testing.md` § Resource lifecycle forbids this;
  the documented consequence is netty channel-pool churn surfacing as timeouts,
  matching #8's timeout and #31's under-load failure).
- **Unclosed sockets** — `HealthCheckHandlerTest` has **no `@AfterEach`**: servers from
  `startServer` are never stored or closed, and `request()` creates a new `HttpClient`
  per call that is never closed.

**Ticket disposition:** #8, #9, #10, #31 have provable defects. #7, #11, #47 already
satisfy the checklist — hardened here, but closed only on sustained CI evidence.

## 3. Slice plan

Each slice is independently committable and compiles on its own. Risk tier is
`routine` throughout: these are test-only changes against a frozen production API,
mechanical against the `testing.md` checklist.

**S1 — persist plan.** This file. Commit: `docs: add implementation plan for flaky-test determinism`.

**S2 — rest-client WireMock lifecycle (confirmed root cause; #10).** Convert the three
violating ITs to the static `@RegisterExtension` shape used by `RestClientRetryIT`.
Normalize the four `static @BeforeAll` classes to the same shape so the module has one
lifecycle idiom. Proof: existing module ITs are the regression suite — they must stay
green while the lifecycle changes underneath them; a stub race would surface as the
documented 404. Commit: `test(rest-client): register WireMock as a static extension`.

**S3 — rest-core correlation (#8, #31).** Hoist `Vertx` + `HttpClient` to class scope
(`@BeforeAll`/`@AfterAll`), matching `RestRequestCompletionEmitterTest`. Pin bind and
connect to `127.0.0.1`. Proof: all existing tests in the class stay green.
Commit: `test(rest-core): share the correlation test client across the class`.

**S4 — management health check (#9).** Add the missing `@AfterEach`; store and close the
server; share one class-scoped client. Pin `127.0.0.1`.
Commit: `test(management): close health-check servers and share the client`.

**S5 — rest-jaxrs hardening (#7, #47).** Pin bind/connect to `127.0.0.1` in
`ProfiledBodyParseIT` and `AnnotationDrivenRoutingIT`; ensure no helper leaks a client
past the `@AfterEach` field. No assertion changes.
Commit: `test(rest-jaxrs): pin loopback bind and connect in routing ITs`.

**S6 — verification.** Full `./mvnw -ntp clean verify`, plus a stress loop on each
touched class. Commit only if fixes are needed.

## 4. Artifact manifest

**New**
- `docs/plans/fix-flaky-test-determinism.md` (this file; removed in the final docs commit)

**Modified**
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/RestClientAttemptObserverIT.java`
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/RestClientConnectionFailureIT.java`
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/RestClientResponseFailureIT.java`
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/RestClientIntegrationIT.java`
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/RestClientUrlIT.java`
- `vertique-rest/vertique-rest-client/src/test/java/dev/vertique/rest/client/DefaultRestClientDispatcherIT.java`
- `vertique-rest/vertique-rest-core/src/test/java/dev/vertique/rest/core/correlation/CorrelationIngressMiddlewareTest.java`
- `vertique-management/src/test/java/dev/vertique/management/HealthCheckHandlerTest.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ProfiledBodyParseIT.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/AnnotationDrivenRoutingIT.java`

**Deleted** — none.

**Module documentation decisions** (test-only change; no application-facing surface moves):
- `vertique-rest-client`: no documentation impact — test lifecycle only, no API/SPI, config, or behavior change.
- `vertique-rest-core`: no documentation impact — test lifecycle only.
- `vertique-management`: no documentation impact — test lifecycle only.
- `vertique-rest-jaxrs`: no documentation impact — test lifecycle only.

## 5. ADRs to write

None. No load-bearing architectural decision is made: every change applies an
existing, recorded convention (`.claude/rules/testing.md`). The one judgment call —
hardening classes with no identified defect — is a scope decision recorded in §1,
not an architectural one.

## 6. Risks & edge cases

- **Hardening without a proven mechanism (#7, #11, #47)** may not stop the flake, and a
  green CI run will not prove it did. Mitigated by making changes strictly subtractive
  so they cannot introduce a *new* flake, and by not closing those tickets on this PR alone.
- **Hoisting `Vertx` to class scope (S3)** changes test isolation: state leaking between
  methods would now persist. Mitigated because the class's assertions are per-request and
  `RestRequestCompletionEmitterTest` already runs this shape safely.
- **WireMock `@RegisterExtension` (S2)** stops per-test server restarts; classes relying on
  a pristine server per method must reset stubs explicitly. Each converted class gets an
  explicit reset so isolation is preserved, not assumed.

## 7. Verification

- Per slice: `./mvnw -ntp -pl <module> -am verify`
- Full: `./mvnw -ntp clean verify` (entire reactor)
- Stress loop per touched class (`testing.md` requires this before any determinism claim):
  `for i in $(seq 50); do ./mvnw -ntp -pl <module> verify -Dit.test=<Class> || break; done`
- Mechanical completeness: no `private WireMockServer` (non-static, per-test) remains in
  `vertique-rest-client`; no `createHttpClient()` inside a `@Test` body in a touched class.
- **CI is the gate** (`testing.md`): the PR opens as a draft and is promoted only on green CI.

## 8. Out-of-scope & deferral routing

- Root-causing #7/#11/#47 — no mechanism identified despite four refuted hypotheses.
  Findings stay attached to those issues; they remain open after this PR.
- Broader sweep of other modules' ITs for the same checklist violations — routed to a new
  GitHub issue if the sweep is wanted; not attempted here to keep the diff reviewable.
