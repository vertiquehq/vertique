<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Plan: #191 — ResponseSerializer completion future — fix/response-serializer-completion

## 1. Context & goal

Release-blocker item 2: `DefaultResponseSerializer.serialize` starts
`stream.pipeTo(httpResponse)` for a `StreamingBody` and discards the returned
`Future<Void>`; the SPI method is `void`. Mid-stream failures and client aborts are
invisible — a truncated SSE/stream is recorded as a clean 200 by metrics **and audit**.

- Why now: SPI + event-record break; pre-release (0.0.0-SNAPSHOT) is the cheap window.
  Every SSE response rides the broken path.
- Boring default / do-less alternative: log-only observation. Rejected: nothing can be
  observed without the returned future; silent truncation persists downstream without
  consumer migration.

**Plan supersedes #191 (explicit, ratified at this gate):** the issue's named remedy —
re-keying the OTel span outcome off pipe completion — is refuted by verified tracer
behavior (span ends inside `conn.write()`; post-end writes silently dropped). This
plan delivers wire-failure observability via the completion event, `error.type`
(D1=A), and the enterprise audit outcome; **OTel span accuracy for streamed aborts
splits out** to a follow-up issue (§10). #191 closes with this supersession note only
after the full pipeline converges and both repos merge.

**Landing (user-directed):** worktree `.claude/worktrees/response-serializer-completion`,
branch `fix/response-serializer-completion` from local `main`. The git-workflow PR
requirement is **explicitly waived by the user for this batch** ("ignore the mandatory
PR", 2026-07-27). **No merge happens before full convergence**: enterprise
compatibility (S5), simplify, full builds, security + Codex review loop, deferral
routing, and the docs gate all complete first (§4 execution order); then framework
merges, then enterprise.

## 2. Pre-flight findings (verified 2026-07-27; dossier + Plan-agent + Codex Vert.x-5.1.2 source extraction + two red-team rounds)

- `ResponseSerializer` (rest-core): single `void serialize(RoutingContext, Response)`;
  one production implementor, zero test implementors, 2 `mock(ResponseSerializer.class)`
  sites (must be stubbed after the signature change).
- Production call sites: `ResponsePipeline.applyToWire` L343; `serializeErrorWithFailOpen`
  L365 + L380 (sync-throw-driven fail-open retry, FR-JSON-058A — must survive unchanged).
- `sendResponse` fires `afterResponse` synchronously after `applyToWire` — wire
  handoff. Single-fire + ordering pinned by `ResponsePipelineCharacterizationTest`.
- **Hard constraint (OTel SP-4):** `afterResponse` stays at wire handoff.
- **Vert.x 5.1.2 facts (source-verified):**
  - `ReadStream.pipe()` → `pause(); new PipeImpl<>(this)`; `Pipe.endOnFailure(boolean)`,
    `.to(WriteStream)` → `Future<Void>`; default pipeTo ends the destination on source
    AND write failures; write failures unwrap `WriteException`.
  - `PipeImpl.to()` calls `src.resume()` **before returning** → serializer-side
    settle-before-end refuted; termination is pipeline-owned.
  - Context-free promises invoke listeners synchronously on the completing thread.
  - **Thread affinity:** nothing constrains where a custom serializer's future
    completes → the pipeline redispatches onto the captured request `Context`.
  - `HttpServerResponseImpl.end(buf)` runs the response `endHandler` **inline before
    `end(buf)` returns** (the write future may already be settled at return); the
    caller cannot attach an observer before return → buffered late write failures are
    not capturable by the exactly-once event (narrowed guarantee, §3).
  - Idle destination close never fails the pipe future → the failed end-handler
    `AsyncResult` input is load-bearing for client aborts.
  - Close path delivers `io.vertx.core.impl.NoStackTraceThrowable` with exact message
    `"Connection closed"`; HTTP/2 resets arrive separately as `StreamResetException`.
    Message-only matching can misclassify unrelated failures → predicate frozen as
    **class + message** (§6).
  - Retry boundary is **initiation** (`write_` throws once ended), not byte commitment.
- End handlers fire synchronously inside the pipeline's own `end()` → marker-before-
  end-handlers deterministic under pipeline-owned termination.
- `RestServerRequestMetricsListener` L112-113: `error.type` only from `failureCode`
  → D1 (resolved Option A).
- **Enterprise consumer:** `RestRequestCompletedAuditProjector` L108 maps outcome via
  `AuditOutcome.fromHttpStatus(e.statusCode(), e.failureCode())`; `AuditOutcome` is a
  public record mapping `<400 → SUCCESS` unconditionally → truncated 200 audits as
  success. Event-record change breaks **30 positional-constructor call sites across 5
  enterprise test files** (§5). Enterprise migration is in-plan (S5) and **gates the
  framework merge**.
- **Active-PRD coupling (red-team round 2):** `prd/product/telemetry-001-metrics-and-
  tracing.md` freezes the 13-field event list (L69) and `error.type (from failureCode)`
  (L132). Both statements change → the PRD is in the manifest and updated at the docs
  gate (meta repo, docs-only).
- `sendFallback500` discards its own `end()` future — adjacent defect, fixed in S2.
- `RestRequestCompletedEvent`: 13-component record; current compact constructor
  validates required non-nulls and defensively copies mutable inputs (read from
  source; frozen with the new component in §6).
- Tests: zero StreamingBody coverage anywhere; harness precedent
  `UploadTempFileCleanupIT` (GatedReadStream, TCP RST via `SO_LINGER(0)`).
- Docs: rest-core + rest-jaxrs `module.md` serializer sections stale (rest-jaxrs also
  claims inverted ordering); otel-rest SP-4 stays valid. ADRs live in the meta repo:
  product ADRs under `adr/product/`, next free number **0189** (re-check at S0 for
  concurrent-session drift).
- Archived PRD `response-serialization-spi.md` freezes the extension model only;
  streaming must never buffer (FR-RESTSER-013/NFR-003).

## 3. Design (frozen after debate + two red-team rounds)

**Dual-channel completion contract:**

> Synchronous throw = **no write or end was initiated**; caller may retry (fail-open).
> Returned future: success = fully written and ended; failure = post-handoff wire
> failure (zero+ bytes possibly written) — never retryable; caller owns terminal
> cleanup. The future **may complete on any thread**; the pipeline redispatches onto
> the captured request `Context`.

- `Future<Void> ResponseSerializer.serialize(RoutingContext, Response)`; `encode()`
  failures stay synchronous throws. `StreamingBody` returns
  `stream.pipe().endOnFailure(false).to(httpResponse)` directly.
- `ResponsePipeline.sendResponse`: capture `Context` → future from `applyToWire` →
  `afterResponse` at handoff (unchanged; pending-future pin) → observer redispatching
  onto the captured context when `Vertx.currentContext()` differs → on failure:
  `KEY_WIRE_FAILURE` (first-writer-wins) + WARN (**class-only**: method/path/status +
  cause class simple name; full throwable at DEBUG) + end-if-not-ended (guarded). No
  bare 500, no hook re-fire, no `sendFallback500` re-entry. `sendFallback500` observes
  its own `end()` future (marker may land post-emission on that path — logging
  guaranteed, enrichment best-effort).
- `serializeErrorWithFailOpen`: identical sync-retry, returning the retry's future.
- Event enrichment: `wireFailureCode` (nullable, low-cardinality). Inputs: marker
  (streaming failures) and failed end-handler `AsyncResult` (client aborts; §6
  predicate). Precedence: marker wins; first failure wins; cleanup failures never
  overwrite. **Late-`end()` failures documented as not covered** — buffered
  `end(buf)`, null-entity `end()`, and a stream's final `end()` alike: Vert.x runs
  the response end handlers inline before `end()` returns, so a failure settling only
  at that point can land after emission (§8 falsifier).
- **D1 RESOLVED: Option A** (user ruling 2026-07-27), unconditional:
  `error.type` = `failureCode`, else `wireFailureCode`, else `none`.
- **Audit outcome (frozen, enterprise, projector-local; SUCCESS-confined per the
  2026-07-28 user-ratified amendment):** `e.wireFailureCode() != null` **and** the base
  outcome's status is `SUCCESS` → `new AuditOutcome(Status.FAILURE,
  String.valueOf(e.statusCode()), e.wireFailureCode())`; a `DENIED`/`FAILURE` base
  outcome is passed through **unchanged** (status, statusCode, reasonCode), so a
  client-triggerable wire failure cannot demote a denial. For non-SUCCESS outcomes the
  wire signal lives in the completion event and metrics only.
- No new interceptor hook (trigger: 2+ consumers). `KEY_WIRE_FAILURE` stays a public
  emitter key (`KEY_OPERATION_ID` precedent).

## 4. Slice plan & execution order

S1, S2, S3, and S5 are red tests → green implementation → commit (two commits each,
stated per slice), and **every commit compiles** — red commits carry whatever minimal
compiling surface the tests need, with behavior still failing. S0 and S6 are docs
slices; S4 is an acceptance gate under an explicit, user-ratified red-first waiver
(see S4). The tree compiles at every boundary in each repo.

**Execution order (prescriptive, merge-last):**

```
[gate] pre-exit linter PASS (done)
S0 persist plan + ADR draft
[gate] persisted-plan linter PASS (outside any slice, between S0 and S1)
S1 → S2 → S3 → S4                      (framework worktree)
simplify framework diff
framework clean verify → clean install -DskipTests
S5 enterprise migration (against that installed snapshot)
simplify enterprise diff → enterprise clean verify
security review + Codex review (parallel) → Codex loop to convergence
  [invalidation rule] after simplify and after EVERY framework-affecting review
  fix: framework clean verify → clean install -DskipTests → enterprise clean
  verify. No merge until this chain passes on the FINAL framework commit.
deferral routing presented to user (step 8.5) → approved issues FILED (pre-merge)
S6 docs gate: telemetry-001 PRD update + module-doc audit + plan-file removal
[merge] framework → local main; then enterprise → local main
post-merge only: ADR 0189 flipped Accepted; #191 closed with supersession note
```

**S0 — persist plan + ADR draft** — routine
Framework: `docs: add implementation plan for response-serializer completion future`.
Meta repo: `docs: add ADR 0189 response-serializer completion future (Proposed)`.
Proof obligations: plan file exists at `docs/plans/fix-response-serializer-completion.md`
verbatim to this artifact; ADR file exists at `adr/product/0189-response-serializer-
completion-future.md` with Status: Proposed and the §6 appendix verbatim.
*(Gate, not a slice: `plan-linter` runs on the persisted file and must PASS before S1.)*

**S1 — SPI + serializer completion futures** — **critical**
Red commit: `test(rest-jaxrs): add failing wire-completion tests for ResponseSerializer`
— carries the 8 named tests PLUS the interface change and a **minimal compiling stub
implementation** (every `DefaultResponseSerializer` branch returns
`Future.succeededFuture()` after its existing behavior), so the commit compiles and
the tests fail **behaviorally** (futures don't mirror end/pipe outcomes). →
Red-commit artifacts: the 8 tests; `ResponseSerializer.java` (new signature +
§6 javadoc); stub `DefaultResponseSerializer` branches; **rest-core `module.md`
(SPI contract section — changes with the interface, same commit)**. →
Green commit:
`feat(rest-core)!: ResponseSerializer.serialize returns wire-completion future`
— artifacts: real per-branch futures per §6; **rest-jaxrs `module.md` (actual
serializer behavior — changes with the implementation, same commit)**.
Tests (`DefaultResponseSerializerTest`):
- `bufferedBodyFutureMirrorsEndFuture` — given a Buffer entity and a controllable
  mock `end(buf)` future / when serialized / then the returned future mirrors it.
- `nullEntityReturnsEndFuture`; `noEncoderFallback500ReturnsEndFuture` — same shape.
- `streamingBodySuccessCompletesAfterFullPipe` — gated stream / completes only after
  all chunks + end.
- `streamingBodyMidStreamFailureFailsFutureWithSourceCause` — async failure / future
  fails; response NOT ended by the serializer.
- `streamingBodyImmediateSourceFailureYieldsFailedFutureAtReturn` — fails on resume /
  already-failed future at return; response not ended.
- `streamingBodyClientWriteFailureFailsFuture` — WriteException unwrapped.
- `encodeThrowRemainsSynchronousWithNothingInitiated`.
(Module docs assigned inside the red/green artifact lists above.)

**S2 — pipeline observation + marker** — **critical**
Red commit: `test(rest-jaxrs): add failing pipeline wire-observation tests` —
artifacts: the 7 named tests; **the public `KEY_WIRE_FAILURE` constant on the emitter
(the tests' marker assertions need it to compile)**; **rest-core `module.md` (the new
key — same commit as the API surface)**; mock stubbing so everything compiles. The
pipeline still discards the future → tests fail behaviorally. →
Green commit: `feat(rest-jaxrs): observe wire-completion future in response pipeline`
— artifacts: pipeline observation + context capture/redispatch + marker population +
fallback end-future handling; characterization stubbing + "wire handoff" wording;
`RequestInterceptor.afterResponse` + `ResponsePipeline` javadoc; **rest-jaxrs
`module.md` (pipeline observation + termination behavior)**; **otel-rest `module.md`
note** — all same commit as the behavior they document.
Tests (`ResponsePipelineTest` + characterization):
- `wireFailureAfterHandoffSetsMarkerAndLogsClassOnly` — marker holds cause; WARN
  contains cause class simple name and NOT the raw message.
- `afterResponseFiresWhileCompletionFutureStillPending` — OTel pin.
- `immediateFailedFutureEndsResponseWithoutHooks`.
- `workerThreadCompletionRedispatchesToRequestContext` — context-free promise
  completed from a worker thread / marker + terminal cleanup run on the request
  context.
- `declaredLengthStreamFailureEndGuarded`.
- `errorPathFailOpenRetryReturnsRetryFuture`.
- `sendFallback500EndFutureObserved` — marker set; may land after the completion
  event emitted (logging guaranteed; enrichment best-effort on this path).
(Module docs assigned inside the red/green artifact lists above.)

**S3 — completion-event enrichment + metrics fallback** — routine
Red commit: `test(rest-core): add failing wire-failure enrichment tests` —
artifacts: the 5 named tests; the record component (§6 frozen position + javadoc);
all constructor call-site migrations (emitter passes `null`); **rest-core `module.md`
(event rows — same commit as the record surface)**. Compiles; tests fail
**behaviorally** (the field is never populated). →
Green commit:
`feat(rest-core): record wire failures on RestRequestCompletedEvent and error.type`
— artifacts: emitter marker/end-handler inputs + normalization; metrics fallback;
**micrometer-rest `module.md` (`error.type` semantic — same commit as the fallback)**.
Tests:
- `emitPopulatesWireFailureCodeFromMarker`.
- `emitNormalizesConnectionClosedFromFailedEndHandler` — cause is
  `NoStackTraceThrowable` with message `"Connection closed"` → `"ConnectionClosed"`;
  a `StreamResetException` keeps its class simple name; an unrelated exception with
  the same message does NOT normalize (class+message predicate pin).
- `emitLeavesWireFailureCodeNullOnCleanCompletion`; `markerWinsOverEndHandlerFailure`.
- `errorTypeFallsBackToWireFailureCode` (micrometer-rest).
(Module docs assigned inside the red/green artifact lists above.)

**S4 — end-to-end acceptance gate** — **critical**
**Explicit red-first waiver (planning-standard deviation — RATIFIED by the user
2026-07-27):** these ITs land after S1–S3 and are expected to pass on arrival — they
are *acceptance proofs* of the integrated behavior, not red-first specs (the
red-first obligations for this feature live in S1–S3's unit tests). Single commit:
`test(rest-jaxrs): prove wire-failure observation end-to-end`.
`StreamingWireFailureIT`; frozen mechanics: `listen(0, "127.0.0.1")` + `actualPort()`;
class `@Timeout(20s)`; shared `HttpClient` `@BeforeAll`/`@AfterAll`; every socket
closed on every exit path; latch/barrier sync; `SO_LINGER(0)` for RST; 10 local runs
before the stress gate. Proof strength check — **one mutation per failure input**,
since the two ITs ride independent seams: (a) marker propagation removed → the
mid-stream source-failure IT must fail; (b) failed end-handler enrichment removed →
the client-abort IT must fail. Both mutations reverted byte-identically (empty
`git diff` on src/main). This substitutes for red-first here.
- `midStreamSourceFailureSurfacesWireFailureCode` — given a resource streaming a
  gated body that fails after N chunks / when a client reads to EOF / then a
  registered listener observes status 200 + non-null `wireFailureCode` and the client
  saw a truncated-but-ended body.
- `clientAbortDuringStreamingSurfacesWireFailureCode` — given a gated stream held
  open / when the client resets the connection (`SO_LINGER(0)`) after the first chunk
  / then the listener observes non-null `wireFailureCode` (`"ConnectionClosed"`).

**S5 — enterprise audit migration + compatibility build** — **critical** (pre-merge gate)
Runs after framework simplify + `clean verify` + `clean install -DskipTests` (per the
§4 order) → enterprise worktree `.claude/worktrees/audit-wire-failure`, branch
`fix/audit-wire-failure`.
Red commit: `test(audit): add failing wire-failure audit outcome test` —
artifacts: `truncatedStreamAuditsAsFailureWithWireReason` (given a 200 event with
`wireFailureCode` / when projected / then outcome Status.FAILURE with reason =
wireFailureCode) **plus all 30 constructor migrations across the 5 named test
files** (compilation surface); the projector is unchanged, so the commit compiles
and the test fails **behaviorally**. →
Green commit: `feat(audit): map wire failures to audit FAILURE outcome` —
artifacts: projector mapping (§3, frozen) only; **audit-rest `module.md` outcome
section (same commit as the behavior change)**.
Gate: full `./mvnw -ntp clean verify` of vertique-enterprise against the installed
snapshot. **Invalidation rule (§4): every later framework-affecting fix re-runs
framework verify → install → enterprise verify; no merge until the chain passes on
the final framework commit.**

**S6 — docs gate (pre-merge)** — routine
Precondition: deferral routing already presented (step 8.5) and **approved follow-up
issues already filed** (`chore: file follow-up issues for deferred review findings`,
issue numbers in the commit body) — filing happens BEFORE this gate and before any
merge.
Meta repo: `docs: update telemetry-001 event and error.type contracts for wire
failures` (L69 event field list + L132 `error.type` derivation).
Framework: `docs: remove implementation plan for response-serializer completion future`.
Proof obligations: telemetry-001 shows the 14-field list and the fallback rule;
`docs/plans/` contains no plan file; module-doc verifier green; the deferral issues
exist in the tracker.
Post-merge only: `docs: mark ADR 0189 as Accepted`; #191 closed with supersession note.

## 5. Artifact manifest

**Modified (framework repo, sources/vertique):**
- `vertique-rest/vertique-rest-core/src/main/java/dev/vertique/rest/core/response/ResponseSerializer.java` (S1)
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/DefaultResponseSerializer.java` (S1)
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/DefaultResponseSerializerTest.java` (S1)
- `vertique-rest/vertique-rest-core/src/main/resources/META-INF/vertique/module.md` (S1, S2, S3)
- `vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md` (S1, S2)
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/ResponsePipeline.java` (S2)
- `vertique-rest/vertique-rest-core/src/main/java/dev/vertique/rest/core/events/RestRequestCompletionEmitter.java` (S2 key; S3 emit)
- `vertique-rest/vertique-rest-core/src/main/java/dev/vertique/rest/core/interceptor/RequestInterceptor.java` (S2, javadoc)
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ResponsePipelineTest.java` (S2)
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ResponsePipelineCharacterizationTest.java` (S2)
- `vertique-opentelemetry/vertique-opentelemetry-rest/src/main/resources/META-INF/vertique/module.md` (S2)
- `vertique-rest/vertique-rest-core/src/main/java/dev/vertique/rest/core/events/RestRequestCompletedEvent.java` (S3)
- `vertique-rest/vertique-rest-core/src/test/java/dev/vertique/rest/core/events/RestRequestCompletionEmitterTest.java` (S3)
- `vertique-micrometer/vertique-micrometer-rest/src/main/java/dev/vertique/micrometer/rest/RestServerRequestMetricsListener.java` (S3)
- `vertique-micrometer/vertique-micrometer-rest/src/test/java/dev/vertique/micrometer/rest/RestServerRequestMetricsListenerTest.java` (S3)
- `vertique-micrometer/vertique-micrometer-rest/src/main/resources/META-INF/vertique/module.md` (S3)

All framework `new RestRequestCompletedEvent(` call sites live in files already
listed above (`RestRequestCompletionEmitter.java`,
`RestRequestCompletionEmitterTest.java`, `RestServerRequestMetricsListenerTest.java`);
the §9 grep is the completeness CHECK on that claim, not a manifest entry.

**New (framework repo):**
- `docs/plans/fix-response-serializer-completion.md` (S0)
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/StreamingWireFailureIT.java` (S4)

**Deleted (framework repo):**
- `docs/plans/fix-response-serializer-completion.md` (S6 — content lives in git history)

**Modified (enterprise repo, sources/vertique-enterprise) — S5:**
- `vertique-audit/vertique-audit-rest/src/main/java/dev/vertique/audit/rest/RestRequestCompletedAuditProjector.java`
- `vertique-audit/vertique-audit-rest/src/main/resources/META-INF/vertique/module.md`
- `vertique-audit/vertique-audit-rest/src/test/java/dev/vertique/audit/rest/AuditRestCaptureCoordinatorTest.java`
- `vertique-audit/vertique-audit-rest/src/test/java/dev/vertique/audit/rest/ProjectorPurityTest.java`
- `vertique-audit/vertique-audit-rest/src/test/java/dev/vertique/audit/rest/RestRequestCompletedAuditProjectorTest.java`
- `vertique-audit/vertique-audit-rest/src/test/java/dev/vertique/audit/rest/HttpServerEvidenceIsolationTest.java`
- `vertique-audit/vertique-audit-rest/src/test/java/dev/vertique/audit/rest/RestAuditProjectionTest.java`

**Modified (meta repo):**
- `prd/product/telemetry-001-metrics-and-tracing.md` (S6: L69 field list, L132
  error.type derivation)

**New (meta repo):**
- `adr/product/0189-response-serializer-completion-future.md` (S0 Proposed; post-merge Accepted)

**Module-doc decisions:** rest-core, rest-jaxrs, opentelemetry-rest, micrometer-rest,
audit-rest (enterprise) — canonical `module.md` updated in-slice as listed.
audit-core (enterprise): no documentation impact — no code change (projector-local
mapping).

## 6. Contract Appendix (frozen)

```java
// vertique-rest-core — dev.vertique.rest.core.response.ResponseSerializer (SPI, breaking)
/**
 * Serializes the given Response body to the HTTP wire.
 *
 * <p>Invoked by the response pipeline for responses requiring serializer-owned body
 * handling, on the request's event-loop context, after the status code and headers
 * have been written to the {@code RoutingContext}'s response and after
 * {@code transformResponse} hooks have run. Normal empty-body and bare fallback paths
 * bypass the serializer entirely; the error fail-open path may retry it exactly once
 * after a synchronous pre-initiation failure (FR-JSON-058A). Implementations MUST NOT
 * block the calling thread.
 *
 * <p>Completion contract (dual-channel):
 * <ul>
 *   <li>SYNCHRONOUS THROW — no write or end was initiated (encode-time failure).
 *       The caller may retry against the same response head (fail-open, FR-JSON-058A).</li>
 *   <li>RETURNED FUTURE — success: the response has been fully written and ended.
 *       Failure: the wire write failed after handoff; zero or more bytes may have been
 *       written; never retryable; the caller owns terminal cleanup (the response may
 *       still need ending). The future MAY complete on any thread — callers must not
 *       assume context affinity; the framework pipeline redispatches handling onto the
 *       request context.</li>
 * </ul>
 *
 * <p>Default implementation, per branch: null entity → future of {@code end()};
 * no matching encoder → future of {@code end(problemJson)} [500]; BufferedBody →
 * future of {@code end(buffer)}; StreamingBody →
 * {@code stream.pipe().endOnFailure(false).to(httpResponse)} — MUST NOT buffer
 * (FR-RESTSER-013 / NFR-003) and MUST NOT end the response on pipe failure.
 *
 * @param ctx      the routing context whose response head has already been written
 * @param response the produced framework response carrying the entity to serialize
 * @return a future settling with wire completion per the contract above; never
 *         {@code null}
 */
Future<Void> serialize(RoutingContext ctx, Response response);
```

```java
// vertique-rest-core — RestRequestCompletionEmitter (API addition)
/** Post-handoff wire-failure marker; value: Throwable; first writer wins. */
public static final String KEY_WIRE_FAILURE = "vertique.rest.core.events.wireFailure";
```

```java
// vertique-rest-core — RestRequestCompletedEvent (record, breaking: component added)
/**
 * Immutable completion event for a terminal HTTP request outcome.
 *
 * @param startTime          instant the middleware registered the request; never null
 * @param endTime            instant completion was observed; never null
 * @param method             HTTP method name; never null
 * @param path               raw request path; never null
 * @param routeTemplate      OpenAPI path template, or null pre-dispatch
 * @param operationId        OpenAPI operationId, or null pre-dispatch
 * @param statusCode         HTTP status actually sent
 * @param failureCode        low-cardinality pipeline-mapped failure classification,
 *                           or null when no processing failure was recorded
 * @param safeFailureMessage curated bounded message, or null — never raw exception text
 * @param wireFailureCode    NEW — low-cardinality post-handoff wire-failure
 *                           classification (cause class simple name, or
 *                           "ConnectionClosed" per the frozen predicate), or null on
 *                           clean wire completion; orthogonal to failureCode — a
 *                           200-status event with non-null wireFailureCode is the
 *                           truncated-response signature
 * @param securityContextSnapshot immutable snapshot, or null when unavailable
 * @param correlationContext immutable snapshot, or null when unavailable
 * @param origin             network-envelope origin; never null as an Optional
 * @param safeAttributes     additional attributes; null/empty tolerated at
 *                           construction, normalized to an unmodifiable copy
 */
public record RestRequestCompletedEvent(
        Instant startTime,
        Instant endTime,
        String method,
        String path,
        @Nullable String routeTemplate,
        @Nullable String operationId,
        int statusCode,
        @Nullable String failureCode,
        @Nullable String safeFailureMessage,
        @Nullable String wireFailureCode,          // NEW — position frozen here
        @Nullable SecurityContextSnapshot securityContextSnapshot,
        @Nullable CorrelationContextSnapshot correlationContext,
        Optional<RequestOrigin> origin,
        Map<String, Object> safeAttributes) {
    // Compact constructor invariants (existing behavior frozen; wireFailureCode is a
    // plain nullable pass-through with no validation):
    //   requireNonNull on startTime/endTime/method/path/origin;
    //   safeAttributes = safeAttributes == null || safeAttributes.isEmpty()
    //           ? Map.of()
    //           : Map.copyOf(safeAttributes);
}
```

- `wireFailureCode` value: the failure cause's class simple name; normalized to
  `"ConnectionClosed"` **iff BOTH** hold:
  `cause.getClass().getName().equals("io.vertx.core.impl.NoStackTraceThrowable")`
  **and** `"Connection closed".equals(cause.getMessage())` (Vert.x 5.1.2 close path —
  version-coupled; documented at the normalization site). `StreamResetException` keeps
  its class simple name. Covers streaming failures + client aborts; **late-`end()`
  failures documented as not covered** (buffered `end(buf)`, null-entity `end()`,
  streaming final `end()`).
- `RequestInterceptor.afterResponse`: wire-handoff semantics; timing unchanged.
- Metrics (D1=A): `error.type` = `failureCode`, else `wireFailureCode`, else `none`.
- Audit (enterprise, projector-local), **confined to SUCCESS base outcomes**:
  `wireFailureCode != null && base.status() == SUCCESS` →
  `new AuditOutcome(Status.FAILURE, String.valueOf(statusCode), wireFailureCode)`;
  `DENIED`/`FAILURE` base outcomes pass through unchanged.
- Pipeline WARN on wire failure: method, path, status, cause **class simple name**
  only; full throwable at DEBUG.

## Class Inventory

| Type | Module | Package | Kind | Visibility | Change |
|---|---|---|---|---|---|
| ResponseSerializer | rest-core | dev.vertique.rest.core.response | interface | SPI | signature (breaking) |
| DefaultResponseSerializer | rest-jaxrs | dev.vertique.rest.jaxrs | class | Internal | impl |
| ResponsePipeline | rest-jaxrs | dev.vertique.rest.jaxrs | class (pkg-private) | Internal | observation + redispatch |
| RestRequestCompletionEmitter | rest-core | dev.vertique.rest.core.events | class | API | +KEY_WIRE_FAILURE; emit inputs |
| RestRequestCompletedEvent | rest-core | dev.vertique.rest.core.events | record | API | +wireFailureCode (breaking) |
| RequestInterceptor | rest-core | dev.vertique.rest.core.interceptor | interface | SPI | javadoc only |
| RestServerRequestMetricsListener | micrometer-rest | dev.vertique.micrometer.rest | class | Internal | error.type fallback |
| RestRequestCompletedAuditProjector | audit-rest (ent.) | dev.vertique.audit.rest | class | Internal | outcome mapping |
| DefaultResponseSerializerTest | rest-jaxrs | dev.vertique.rest.jaxrs | class | Test-fixture | new streaming coverage |
| ResponsePipelineTest | rest-jaxrs | dev.vertique.rest.jaxrs | class | Test-fixture | observation tests + stubbing |
| ResponsePipelineCharacterizationTest | rest-jaxrs | dev.vertique.rest.jaxrs | class | Test-fixture | stubbing + wording |
| RestRequestCompletionEmitterTest | rest-core | dev.vertique.rest.core.events | class | Test-fixture | enrichment tests |
| RestServerRequestMetricsListenerTest | micrometer-rest | dev.vertique.micrometer.rest | class | Test-fixture | fallback test |
| StreamingWireFailureIT (+GatedReadStream fixture) | rest-jaxrs | dev.vertique.rest.jaxrs | class | Test-fixture | new |
| AuditRestCaptureCoordinatorTest | audit-rest (ent.) | dev.vertique.audit.rest | class | Test-fixture | ctor sweep |
| ProjectorPurityTest | audit-rest (ent.) | dev.vertique.audit.rest | class | Test-fixture | ctor sweep |
| RestRequestCompletedAuditProjectorTest | audit-rest (ent.) | dev.vertique.audit.rest | class | Test-fixture | ctor sweep + new test |
| HttpServerEvidenceIsolationTest | audit-rest (ent.) | dev.vertique.audit.rest | class | Test-fixture | ctor sweep |
| RestAuditProjectionTest | audit-rest (ent.) | dev.vertique.audit.rest | class | Test-fixture | ctor sweep |

## 7. ADRs to write

- `adr/product/0189-response-serializer-completion-future.md` (S0 Proposed;
  post-merge Accepted): dual-channel contract; pipeline-owned termination; context
  redispatch; afterResponse at handoff (OTel SP-4); event+metrics+audit consumer set;
  narrowed buffered guarantee; #191 supersession; D1 ruling + metric-semantic change;
  close-normalization predicate + version coupling.

## 8. Risks & edge cases

- Immediate synchronous pipe failure: pinned S1+S2.
- Arbitrary-thread completion: pipeline redispatch; pinned by the worker-thread test.
- Late-`end()` failures not captured — the whole class (buffered `end(buf)`,
  null-entity `end()`, streaming final `end()`), documented; falsifier: a test showing
  the routing-context end `AsyncResult` failing at emission for any failed late
  `end()` reopens that coverage.
- Declared Content-Length premature-end: **resolved as-built and bytecode-verified**
  in Vert.x 5.1.2 — `Http1ServerResponse.end()` performs no length check at all, so
  the pipeline **resets** (never ends) any non-chunked response whose declared
  `Content-Length` does not equal `bytesWritten()`, failing closed on an unparseable
  length; a failed reset falls back to closing the connection on HTTP/1 and to nothing
  further on HTTP/2. Guarded + tested.
- HEAD-after-transform entity reintroduction: cheap characterization assertion or doc
  note in S2.
- Characterization tests: stubbing + wording only; a semantic re-pin falsifies the
  design and reopens the plan.
- Wire-vs-observer consistency: no status rewrite after handoff.
- Log content: class-only WARN; tested.
- Enterprise coupling: S5 verifies against the installed FEATURE snapshot and gates
  the framework merge; a failure blocks the batch. Installs follow the §4
  invalidation rule verbatim — after simplify and after EVERY framework-affecting
  review fix: framework clean verify → clean install -DskipTests → enterprise clean
  verify; merge only after the chain passes on the final framework commit. Each
  install touches the shared ~/.m2 SNAPSHOT (the other active session is a known
  concurrent user) — run it immediately before its enterprise build to minimize the
  window.
- OTel residual: span outcome for streamed aborts deferred (supersession, §1).

## 9. Verification

- Per slice (S1–S3, S5 red-first; S4 under its ratified waiver): module-scoped
  `./mvnw -ntp -pl <modules> -am verify` (S1 rest-core+rest-jaxrs; S2 rest-jaxrs; S3
  rest-core+micrometer-rest; S4 the IT 10x locally; S5 per its slice).
- Convergence phase — **the §4 order verbatim, no paraphrase**: S1→S4 → simplify
  framework diff → framework `clean verify` → `clean install -DskipTests` → S5 →
  simplify enterprise diff → enterprise `clean verify` → security + Codex reviews and
  loop, with the invalidation rule (every framework-affecting fix re-runs framework
  verify → install → enterprise verify) → deferral routing + issue filing → S6 docs
  gate → merges. 50-iteration stress on `StreamingWireFailureIT` and the emitter test
  class runs in this phase.
- Mechanical completeness: `grep -rn "pipeTo(" vertique-rest/ --include=*.java` → zero
  production hits; `grep -rn "void serialize(RoutingContext"` → zero; constructor
  sweep greps clean in BOTH repos; rest-jaxrs `module.md` ordering wording matches
  code; telemetry-001 L69/L132 match the shipped contracts.
- Acceptance: mid-stream failure + client abort observable via `wireFailureCode`
  (e2e IT); truncated-200 carries `error.type` (metrics) and audits as FAILURE
  (enterprise); SSE example green; fail-open retry tests unchanged.

## 10. Out-of-scope & deferral routing

GitHub issues in **`vertiquehq/vertique-dev`** (the active tracker per the user's
2026-07-27 ruling — the old `mikakoivisto/vertique` is legacy-only; do not file
there), filed pre-merge after the user sees the summary; one per item with its
re-entry trigger:

- "rest-jaxrs: honest termination for failed streaming responses (reset vs end)" —
  incl. pre-first-byte 500; client-visible wire change. **Partially delivered on this
  branch** (an under-length fixed-length response is now reset instead of ended — see
  the F1-m2 as-built amendment); the remainder is the pre-first-byte 500 and the
  **HTTP/1.0 caveat**: an HTTP/1.0 streamed response is close-delimited rather than
  chunked, so a truncation is indistinguishable from a clean end at the protocol level
  (pre-existing Vert.x behavior — record it in the future issue body).
- "rest-core: capture **late `end()`** write failures (buffered `end(buf)`,
  null-entity `end()`, streaming final `end()`) in completion events" —
  trigger: operational need + §8 falsifier.
- "rest-core: afterResponseSettled interceptor hook" — trigger: 2+ consumers.
- "rest-jaxrs: HTTP/2 RST_STREAM variant of StreamingWireFailureIT" — trigger: h2
  declared supported.
- "opentelemetry-rest: span outcome accuracy for streamed aborts" — the #191
  supersession remainder; trigger: verified tracer end-timing during aborts.
- "micrometer-rest: dashboard adoption of wireFailureCode beyond error.type".
- "rest-core: typed serializer result API" — trigger: first async pre-write encoder.

## Debate outcome (codex-architect session 019fa37a-3f23-76a3-bf16-3e441cf17016 + two user red-team rounds)

**Codex round (adopted, source-verified):** settle-before-end refuted → pipeline-owned
termination; buffered guarantee narrowed + falsifier; retry boundary = initiation;
dual-input necessity; KEY_WIRE_FAILURE in S2; S4 critical; pending-future pin;
declared-length guard; sendFallback500 end-future; ADR at S0. Rejected: recorder
abstraction; unconditional HTTP/2 IT. Escalated D1 → user ruled Option A.

**Red-team round 1 (adopted):** #191 supersession explicit (ratified at gate);
enterprise audit migration (projector mapping frozen, 30 sites/5 files, compatibility
gate); thread-affinity contract + worker-thread test; Class Inventory + frozen event
record + concrete enterprise manifest; D1 conditionals removed; end-handler timing
wording corrected; close predicate frozen; S4 mechanics spelled out; WARN class-only.
**Waived by user:** mandatory-PR flow (local merges stand).

**Red-team round 2 (adopted):** merge moved AFTER full convergence (enterprise gate,
simplify, builds, reviews, deferral routing, docs — §4 order); S5 builds against the
locally installed FEATURE snapshot, not merged main; active telemetry-001 PRD added
to the manifest with both frozen statements updated at the docs gate; persisted-plan
linter positioned as a gate between S0 and S1 (outside slices); close-normalization
predicate hardened to class+message; Class Inventory extended with all test types;
record appendix carries full javadoc + compact-constructor invariants; explicit
red→green→commit wording per slice; S0/S6 proof obligations; Deleted manifest
section; ADR number allocated (0189 — renumbered from 0188 at the S0 drift check); commit scopes corrected
(`docs:` unscoped for meta repo; `feat(audit):`).

**Red-team round 3 (adopted):** snapshot-invalidation rule frozen in the §4 order
(after simplify and every framework-affecting review fix: framework verify → install
→ enterprise verify; merge only after the chain passes on the final framework
commit); `safeAttributes` freeze corrected to the code's actual null/empty→`Map.of()`
normalization; record + serializer javadoc completed to full `@param`/invocation-
context contracts; deferral-filing contradiction resolved (issues filed pre-S6,
pre-merge; only ADR acceptance and #191 closure post-merge); distinct red-test
commits added to S1/S2/S3/S5; S4 reframed as an acceptance gate with an explicit
red-first waiver + mutation-check substitute; manifest fully concrete
(`RestServerRequestMetricsListenerTest.java` path; grep demoted to a completeness
check); Class Inventory gains the package column and standard Test-fixture
visibility; rest-core + rest-jaxrs `module.md` added to S2's same-slice docs.

**Red-team round 5 (adopted — commit-boundary assignments):** every red and green
commit now carries an explicit artifact list; S5's 30 constructor migrations moved to
the red commit (compilation surface; projector unchanged → behavioral failure), green
= projector mapping only; S2's `KEY_WIRE_FAILURE` constant moved to the red commit
(the tests' compiling surface); module docs assigned to exact commits (S1 red:
rest-core SPI section / S1 green: rest-jaxrs serializer behavior / S2 red: rest-core
key / S2 green: rest-jaxrs pipeline + otel note / S3 red: rest-core event rows / S3
green: micrometer error.type / S5 green: audit-rest outcome). Deferral tracker
corrected to `vertiquehq/vertique-dev` per the user's same-day ruling (old repo is
legacy-only).

**Red-team round 4 (adopted — consistency sweep, §4 canonical):** S1/S3 red commits
made compiling (stub implementation / null-passing constructor migrations; tests fail
behaviorally); serializer cardinality corrected ("serializer-owned body handling;
empty-body and bare fallback paths bypass; fail-open may retry exactly once");
S4 mutation proof split by failure input (marker → source-failure IT; end-handler
enrichment → abort IT); §8 and §9 now repeat the §4 order and invalidation rule
verbatim instead of paraphrasing; manifest module-doc slice annotations corrected
(rest-core S1/S2/S3; rest-jaxrs S1/S2); S1 test count corrected to 8; blanket
red-first wording exempts S0/S4/S6. **S4 red-first waiver + two-path mutation
substitute RATIFIED by the user (2026-07-27).**

## Amendments

- 2026-07-27 (S0 drift check): ADR renumbered 0188 → 0189 — the concurrent session claimed 0188 (static-starter-aggregates) between plan approval and persistence. Verified-fact correction; no contract change, no sign-off required.
- 2026-07-28 (S4 as-built): client-abort wire-failure classification is platform-
  dependent — a hard RST surfaces as `SocketException` on macOS, `"ConnectionClosed"`
  only when Vert.x's close path (NoStackTraceThrowable) delivers it. The §6 predicate
  is unchanged (it is an iff-normalization, not a yield claim), but the IT asserts
  non-null + low-cardinality shape instead of pinning the value, and S6 docs must not
  claim a client abort *yields* "ConnectionClosed". Verified-fact correction.
- 2026-07-28 (S4 discovery → review loop): a plain `ReadStream` entity through
  `ReadStreamBodyEncoder` produces neither Content-Length nor chunked transfer
  encoding (the IT wires `setChunked(true)` via an interceptor; SSE sets it itself).
  Potential adjacent production defect in the same subsystem — handed to the
  convergence-phase review/triage for fix-now-vs-defer adjudication, not silently
  absorbed or dropped.
- 2026-07-28 (Blocker-2 fix — build regression, examples/vertique-example-parent):
  `examples/vertique-example-parent/pom.xml` is `packaging=pom`, and
  flatten-maven-plugin does not replace the install POM for pom-packaging modules
  unless explicitly told to. The module was installing its *raw* POM, so the
  installed POM's `<parent><version>${revision}</version>` was unresolvable from a
  repository — breaking downstream resolution of every `vertique-example-*`
  artifact (surfaced by the enterprise audit integration-tests module). Fix: add to
  `examples/vertique-example-parent/pom.xml`'s `<build><plugins>`:
  ```xml
  <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>flatten-maven-plugin</artifactId>
      <configuration>
          <updatePomFile>true</updatePomFile>
      </configuration>
  </plugin>
  ```
  (inherits the root `pom.xml` `pluginManagement` version, `resolveCiFriendliesOnly`
  flattenMode, and execution bindings — only `updatePomFile` is overridden locally.)

  Proof commands (isolated repository, `~/.m2` untouched), run from the worktree with
  `REPO=/private/tmp/claude-501/-Users-mikakoivisto-Development-VertiqueHQ-vertique-dev/9e8f1bcb-b96e-41fb-943d-e27b2735b03b/scratchpad/flatten-proof-repo`:

  1. Negative control (fix stashed): `./mvnw -ntp -pl examples/vertique-example-parent -am install -DskipTests -Dmaven.repo.local="$REPO"` — build succeeded, but the
     installed POM at
     `$REPO/dev/vertique/vertique-example-parent/0.0.0-SNAPSHOT/vertique-example-parent-0.0.0-SNAPSHOT.pom`
     carried `<parent><version>${revision}</version>` verbatim, confirming the
     regression reproduces in an isolated repo.
  2. Fix restored, same command re-run against the same `$REPO` — installed POM's
     `<parent>` block now reads `<version>0.0.0-SNAPSHOT</version>`.
  3. External fixture (outside the worktree, at
     `/private/tmp/claude-501/.../scratchpad/flatten-fixture/pom.xml`, an empty
     jar module with `<parent>dev.vertique:vertique-example-parent:0.0.0-SNAPSHOT</parent>`
     and an explicit empty `<relativePath/>` to force repository-only resolution):
     `./mvnw -ntp -f "$FIXTURE" help:effective-pom -Dmaven.repo.local="$REPO"` — `BUILD
     SUCCESS`; the effective POM shows the fully resolved parent chain
     (`vertique-example-parent` → `vertique-parent`, BOM import, full
     dependencyManagement) with no unresolved `${revision}` anywhere except the
     source `<revision>0.0.0-SNAPSHOT</revision>` property definition itself. (A
     benign `[WARNING] Failed to build parent project for
     dev.vertique:vertique-example-parent:pom:0.0.0-SNAPSHOT` is emitted first —
     that is Maven's reactor-membership probe failing before it falls back to
     repository resolution, not a resolution failure; the effective POM that
     follows proves the fallback succeeded.)
  4. `./mvnw -ntp spotless:apply -pl examples/vertique-example-parent` — `BUILD
     SUCCESS`, no formatting changes produced.

  **User ruling on Blocker-1 (enterprise pin, unrelated build regression surfaced by
  the same audit):** the committed enterprise `pom.xml` pin stays unchanged for S5.
  The enterprise verification gate instead re-runs in an isolated temporary checkout
  with an explicit, uncommitted two-line compatibility repin — `pom.xml` L10 and L36,
  `0.0.0-repository001.3e841ecb40a9-SNAPSHOT` → `0.0.0-SNAPSHOT` — plus
  `-Drevision=0.0.0-SNAPSHOT` and an isolated `-Dmaven.repo.local`; the result is
  reported as "passed with compatibility override," not as a clean gate pass. The
  permanent, resolvable pin is assigned to the active 0.1.0 / managed-checkout work
  and must update the enterprise pin and its checkout verifier atomically in that
  follow-up, not here.
- 2026-07-28 (review round 1, F7 — **Amendment 3 REFUTED**): the 2026-07-28 "S4
  discovery" amendment above — claiming a plain `ReadStream` entity through
  `ReadStreamBodyEncoder` produces neither `Content-Length` nor chunked transfer
  encoding — is **wrong and is withdrawn**. Verified against Vert.x 5.1.2
  `Http1ServerResponse.write` bytecode: when neither `Transfer-Encoding` nor
  `Content-Length` is set, the first write applies chunked transfer encoding
  automatically (HTTP/1.1). Production `ReadStream` resources are therefore correctly
  framed with no interceptor, and there is no adjacent production defect and no
  follow-up issue to file. `StreamingWireFailureIT`'s `ChunkedResponseInterceptor` is
  retained only to make the test's framing explicit, and its javadoc now says so.
  Verified-fact correction; no contract change, no sign-off required.
- 2026-07-28 (review round 1, F3 — §10 D2 scope widened): the deferred item "capture
  buffered `end(buf)` late write failures" is widened to "capture **late `end()`**
  write failures (buffered `end(buf)`, null-entity `end()`, streaming final `end()`)".
  The exactly-once completion event misses the whole late-`end()` class, not just the
  buffered branch — Vert.x runs the response end handlers inline before `end()`
  returns, so any failure settling only at that point can land after emission. The
  carve-out is now documented on `RestRequestCompletedEvent`, in rest-core's
  `KEY_WIRE_FAILURE` and event sections, in rest-jaxrs's fallback-500 paragraph, and
  on micrometer-rest's `error.type` coverage note. Scope-of-deferral correction; no
  contract change, no sign-off required.
- 2026-07-28 (security review, F1-m2 as-built): the declared-length wire-failure path
  now **resets** the response instead of relying on a guarded `end()`. The mechanism
  named in the security report was wrong — it assumed `end()` raises
  `IllegalStateException` when the declared `Content-Length` is unsatisfied. Bytecode
  verification of Vert.x 5.1.2 `Http1ServerResponse.end()` shows **no length check at
  all**: the under-length end succeeds, fires the end handlers, and leaves a
  framing-violating body (declared N, delivered M<N) on a live keep-alive connection —
  a response-desync vector. The shipped remedy is `HttpServerResponse.reset()`
  (stream-scoped: RST_STREAM on h2, connection close on h1) when the truncated body can
  no longer satisfy the declared length, with `reset()` also as the backstop when the
  guarded `end()` throws or fails its future; chunked responses keep the clean `end()`.
  §8's "Declared Content-Length premature-end: guarded + tested (unverified in 5.1.2 —
  guard, don't assume)" watch-item is resolved as-built, and the surviving synchronous
  `IllegalStateException` causes are an already-written race and a foreign-thread
  write. Verified-fact correction; no contract change, no sign-off required.
- 2026-07-28 (review round 1, USER-RATIFIED): §6 audit mapping amended — the enterprise
  wire-failure override is confined to base outcomes with Status.SUCCESS (F2 Option A).
  DENIED/FAILURE base outcomes are fully unchanged (status, statusCode, reasonCode); the
  wire signal for non-SUCCESS outcomes lives in the completion event and metrics only.
  Rationale: a client-triggerable wire failure (abort-after-request) must not demote
  DENIED — denial monitoring keyed on outcome.status stays trustworthy. ADR 0189
  amended in the meta repo (18c1fb7); enterprise commits d86b339/735c7fb implement and
  pin the confined mapping. Contract re-frozen with user sign-off per the amendment
  protocol.
- 2026-07-28 (process): the first invalidation-chain run produced a phantom
  "compatibility break" (installed rest-core jar missing wireFailureCode) caused by two
  Maven processes racing one worktree target/ — evidence discarded, chain re-run
  serialized on the final commits. Builds within one worktree are serialized from here
  on.
- 2026-07-28 (review round 2, normative sync): the plan's normative sections still
  carried text this ledger had already superseded, so they were edited in place to the
  operative contract: §3 and §6 now state the **generalized late-`end()` carve-out**
  (buffered `end(buf)`, null-entity `end()`, streaming final `end()`) instead of the
  buffered-only exclusion; §3 and §6 now state the **SUCCESS-confined** enterprise audit
  override instead of the unconditional one; §8's declared-`Content-Length` watch-item is
  restated as bytecode-verified and resolved as-built (non-chunked
  `declaredLength != bytesWritten()` → reset, fail-closed on an unparseable length, with
  an HTTP/1 connection-close backstop when the reset itself fails). §2's `end(buf)`
  bytecode finding is left as written — it is an accurate fact about that method; the
  wider class it implies is now stated in §3/§6/§8. Verified-fact and
  scope-of-deferral alignment only; no contract change, no sign-off required.
