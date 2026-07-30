# Plan — a Vert.x 4xx set alongside a non-`HttpException` cause must survive to the response

Successor to the `tingly-hatching-plum` probe slice. Code repo `sources/vertique`, existing worktree
`.claude/worktrees/issue-220-multipart-parts`, existing branch `fix/issue-220-multipart-part-count`
(already carries the red tests at `96516a2` and `8b9673d`).

## 1. Context & goal

Legacy #220 alleged that `maxBodySize` bounds total bytes while leaving the *number* of multipart parts
unbounded. **The probe disproved that** — `http.maxFormFields` (256) is applied and the decoder counts
every part, file and text, against one shared counter. The documentation correction shipped as
vertiquehq/vertique#16 (CI green).

The probe found a real defect while settling the premise: **the rejection answers 500, not the 400
Vert.x intends.** Root cause is not multipart-specific. `JaxRsRouterMount.handleFailure` honors a
*bare* failure status but discards a status that arrives *alongside a non-`HttpException` cause*.

Intended outcome: the status the Vert.x layer deliberately set reaches the client, with a coherent
RFC 9457 body, and the precedence that governs it becomes a recorded decision rather than an
implication of one test's name.

**Why now.** Three tests are already committed red on this branch; it cannot merge until this is
resolved either way.

**The do-less alternative, and why it loses.** Register one `TooManyFormFieldsException → 400`
mapping. Smallest diff, turns the three tests green. It fails on merits, not taste: it would be the
repo's first Netty import in production code against an undeclared transitive dependency (F8), the
message-regex workaround the repo established for exactly this is unavailable because the message is
null (F9), and it would fix one exception while leaving the seam — and the auth defect at F15 — broken.

## 1a. Amendments — this file amends `tingly-hatching-plum.md`

`tingly-hatching-plum.md` is the plan of record; **this file is its amendment**, not a parallel plan.
The probe slice it authorized has run and reported, which is exactly the trigger it named ("the rest of
#220/#223 is designed *after* it reports"). On approval this content replaces it: it persists to
`sources/vertique/docs/plans/fix-issue-220-multipart-part-count.md` as S1 and the predecessor file is
deleted, so only one plan is live.

### Amendments log

| Date | Trigger | What changed |
|---|---|---|
| 2026-07-30 | The predecessor's own trigger fired — its probe slice ran and reported | Replaced its "Vert.x rejects → documentation-only" outcome with production slices S3–S4; froze fixed 200/400 oracles in place of "rejected or accepted"; corrected its F4 (`maxFormBufferedBytes`) and its `@FormParam List<FileUpload>` signature (F21) |
| 2026-07-31 | `codex-architect` debate + three `plan-linter` rounds | Strict **4xx-only** guard (a `>= 400` guard would have regressed five test classes, F14); keep the raw cause unwrapped; ADR-0205; repo-attributed manifest; 50-run stress loop and draft-PR rule |
| 2026-07-31 | Security review | **Reversed the body policy** — `detail` is now cleared unconditionally rather than "preserved when authored", which would have published arbitrary JWT-validator exception messages; **internalized** the hint key instead of broadening it; **merged** the status and sanitization slices (the split emitted the corrected status *carrying* the leaked message); added direct mount-based JWT proof |
| 2026-07-31 | Execution-start drift check (S1) — `origin/main` advanced past the plan's base when vertiquehq/vertique#16 merged | Fact correction, no contract change: `vertique-rest-core` `module.md` key references shift `703→705` and `734→736`. All other cited line numbers re-verified unchanged (`RequestInterceptor.java:88,:91`; rest-jaxrs `module.md:370`; `JaxRsRouterMount.java:437-448`, `:384-385`; `ErrorPipeline.java:177-192`) |
| 2026-07-31 | Governance-landing review | S6 becomes a **governance PR** advancing the `sources/vertique` gitlink, not a docs-only fast-forward; amendment log dated |

Every review finding against the predecessor, and where it is resolved:

| Predecessor finding | Resolution here |
|---|---|
| **(Critical)** Result matrix routes "Vert.x rejects" to documentation-only; the real third outcome is Vert.x intends 400 while Vertique returns 500 | F13/F15; **production slices S3–S4**. #220 is explicitly *not* closed as docs-only — the doc half already shipped separately as vertiquehq/vertique#16 |
| **(Critical)** Oracle left as "rejected or accepted"; "the report, not a green build, is the deliverable" | §4 freezes fixed **200/400** oracles as a consumer-visible contract table; §5 names the green implementation per slice |
| **(Warning)** F4 overstates `maxFormBufferedBytes` | Corrected in §9: it bounds only retained *undecoded* bytes, is **not** a #220 mitigation, and nothing here relies on it |
| **(Warning)** `@FormParam List<FileUpload>` does not compile | **F21** freezes the unannotated aggregate `List<FileUpload>`, which the committed test already uses |
| **(Warning)** Unplanned fifth cleanup test with `Thread.sleep`; `MultipartBodies.java` modified but undeclared | Both declared: the quiescence poll is retained **with justification** in S2 (a naive count-zero check is a test that cannot fail); `MultipartBodies.java` is now a manifest row |
| **(Warning)** No risk tier, manifest, module-doc disposition, ADR, persisted-plan gate, or full `clean verify` | §5 tiers every slice; §6 manifest with repo attribution; four module-doc decisions; ADR-0205; S1 persists; §8 runs full `clean verify` |
| **(Suggestion)** Mixed test proves 257 rejected but not 256 accepted | S2 adds `mixedTextAndFilePartsAtLimitAccepted` (200 text + 56 file) as the matched pair |
| **(Suggestion)** 30 s class timeout undocumented | S2 justifies it inline (per-test servers + a 10 s quiescence cap) |
| **(Proof gap)** No 50-run stress loop; CI not green | §8 adds the 50-run loop for both ITs and the **draft-PR** rule; CI is the gate |
| **(Proof gap)** No architect consultation or passing plan-linter | §3 records the debate (`gpt-5.6-sol`, session `019fb295-…`); plan-linter **PASSes** after three rounds |
| **(Residual risk)** #223 pre-auth parsing/spooling | Untouched and kept separate, as the predecessor intended |

## 2. Pre-flight findings (verified against code and runtime, not assumed)

- **F1 — the failure's exact shape**, captured via an `ErrorInterceptor` in the IT (`8b9673d`),
  because this module's test classpath has no SLF4J provider so the mapper's ERROR is swallowed:
  `cause=…HttpPostRequestDecoder$TooManyFormFieldsException, message=null, ctx.statusCode()=400,
  vertxStatusHint=null`. Deterministic ×3, identical for 257-file / 257-text / 57+200 bodies.
- **F2** Vert.x already computed the right answer: `ctx.statusCode() == 400`.
- **F3** `handleFailure` (`JaxRsRouterMount.java:437-448`) reads `ctx.statusCode()` **only** when
  `cause == null`, and stashes `VERTX_STATUS_CODE_KEY` **only** for an `HttpException`.
- **F4** `ErrorPipeline.applyVertxStatusCodeFallback` (`ErrorPipeline.java:177-192`) exists to restore
  such a status and no-ops here purely because the key is absent.
- **F5** `TooManyFormFieldsException` has a no-arg-only constructor → `getMessage()` and `getCause()`
  are both null.
- **F8** Zero `io.netty.*` imports in any production source; Netty declared in **no** pom (transitive
  via Vert.x 5.1.2). `WebSocketUpgradeExceptions.translate` deliberately matches a Netty exception by
  message regex instead of importing it; `prd/product/websocket-001-streaming-client.md` states "no raw
  netty/Vert.x types in the failure path".
- **F9** That regex escape hatch is unavailable here — the message is null (F5).
- **F10** `RestModule.defaultExceptionMapper()` (`RestModule.java:233-303`) is the only
  `new DefaultExceptionMapper()` in production code, and is deliberately `public static` so
  out-of-package tests can wire the real defaults.
- **F11** `ProblemDetail` is `@JsonInclude(NON_NULL)`, so a null `detail` is simply omitted.
- **F12 (corrected)** `applyVertxStatusCodeFallback` rebuilds via `toBuilder().status(v).build()`,
  which **bypasses the `title` derivation `ProblemDetail.of()` performs** (`ProblemDetail.java:64-72`
  sets `title = titleForStatus(status)`; the *argument* becomes `detail`, not the title). The
  contradictory body I first reported is real but arises narrowly: `titleForStatus(500)` happens to
  equal the literal the catch-all passes as `detail`, so overriding to 400 emits
  `{"title":"Internal Server Error","status":400,"detail":"Internal Server Error"}`. Pre-existing and
  reachable today on the 401/403 path.
- **F13 — the asymmetry, precisely.** `BodyHandlerImpl` fails a body-limit breach with
  `context.fail(413)` — bare status, no throwable (`:104, 244, 313`), and `RoutingContextImpl.fail(int)`
  leaves `failure` null (`:193-196`) → F3's first branch → correct 413. The part-count failure arrives
  from `BodyHandlerImpl`'s request exception handler: `if (t instanceof DecoderException) { sc = 400; …
  } context.fail(sc, t)` (`:267-278`) — status **and** cause. So: **a bare status is honored; a status
  carrying a non-`HttpException` cause is discarded.**
- **F14 — the trap.** `RoutingContextImpl.fail(Throwable)` **forces `statusCode = 500`** (`:199-206`),
  and the fallback *overrides* a status a framework default mapper produced — pinned by
  `ErrorPipelineTest.java:136-161` (`fallbackOverridesDefaultMapperStatus`). `hasSpecificMapper` is
  true **only for application-contributed** mappers (`ExceptionMapperRegistry.java:47-55, 113-133`), so
  framework defaults do get overridden. A `>= 400` guard would therefore stash a synthesised 500 and
  overwrite correct 400s — regressing `WebValidationGateIT.java:121-143`,
  `MultipartFilePartValidationIT`, `FileVerifierRejectionIT`, `MagicBytesVerifierRouteIT`,
  `ProfiledBodyParseUnderGateIT`, and **this branch's own** IT. It would also turn
  `SecurityPolicyEnforcer.java:431`'s app-level 503/403 into 500.
  **Hence a strict 4xx guard (`400 <= sc < 500`).** "Activate only on 500" is also wrong:
  `ParamConverterNotFoundException` intentionally maps to 500 (`RestModule.java:268`), so status alone
  cannot distinguish intentional-500 from catch-all-500.
- **F15 — the fix repairs an auth defect.** `JwtClaimsValidatorContributor.java:111` does
  `ctx.fail(401, e)` with whatever the claims validator threw. Today that renders **500**; after the
  fix, **401**. No mount-based test covers it (`ActionOnlyRouteClaimsValidatorIT` builds its router
  without `JaxRsRouterMount`). A JWT claims rejection currently reporting as a server fault is a real
  defect, and its correction is consumer-visible.
- **F16 — the whole decoder family is fixed for free.** `BodyHandlerImpl:267-278` maps *any*
  `DecoderException` to 400, so `TooLongFormFieldException` and `ErrorDataDecoderException` are covered
  **without naming a Netty type**.
- **F17** `JaxRsRouterMount.java:384-385` javadoc wrongly claims the 413 arrives as an `HttpException`
  (F13 refutes it).
- **F18** No test anywhere sets or exceeds `maxBodySize`; the documented 413 is unproven.
- **F21 — the aggregate upload binding, frozen.** The all-uploads parameter shape is the **unannotated**
  `List<FileUpload>`; a literal `@FormParam List<FileUpload>` does **not** compile, because
  `FormParam.value()` has no default. The committed IT already uses the unannotated aggregate; the
  predecessor plan's prose was wrong on this point.
- **F19 — no external consumer of the key.** `grep` over `sources/vertique-enterprise` for
  `VERTX_STATUS_CODE_KEY` / `vertxStatusCode` / `applyVertxStatusCodeFallback`: **zero hits**. Project
  is `0.1.0-SNAPSHOT`, unreleased.
- **F20 — the red IT is currently blind to regressions.** `MultipartPartCountLimitIT.java:352-356`
  builds a *fresh* `DefaultExceptionMapper` carrying only a `RestValidationException` mapping — no
  catch-all, none of the built-ins. It would go green on the fix while unable to observe any
  4xx-becomes-500 regression. Every regression test must wire `RestModule.defaultExceptionMapper()`
  (F10).

### Corrections to the predecessor plan

Its **F6** ("a rejected request does not leak files") is confirmed, but for an unstated reason: cleanup
survives even a `BodyHandler`-originated failure that bypasses the `MIN_VALUE + 1` end-handler
registration. An intermediate probe run appeared to show a leak; that was a shared-uploads-directory
artifact in my own test, disproved by per-test isolation plus an external filesystem poll (161 spooled
→ 0 in ~26 ms).

## 3. Independent first opinion & debate outcome

`vertique-toolkit:vertique-codex-architect` (`gpt-5.6-sol`, high), session
`019fb295-94d2-7f73-94e3-91a8a5049b05`. Verdict: **right seam, unsafe implementation.**

**Adopted:**
- Strict **4xx-only** guard, not `>= 400` (F14). Load-bearing correction.
- **Keep the raw cause; do not wrap** in `WebApplicationException`, so an application-registered
  `ExceptionMapper` for the underlying type still matches.
- **Do not touch** `hasSpecificMapper` or the fallback activation predicate — it is a *tested,
  deliberate* contract (F14), not the defect I suspected. I retract that hypothesis.
- Change 2 belongs in this PR, but **narrower** than I proposed.
- **ADR the precedence chain**; it is currently implied only by a test name.
- Regression tests must use the real framework defaults (F20).

**Rejected, with reasons:**
- *Typed framework exception for decode failures* (which `testing.md` would normally prescribe):
  rejected on merits, not impossibility. It would duplicate a classification Vert.x already made, fail
  silently on a Netty rename/shade, and create a permanent public catch contract with no current
  consumer. `testing.md:82-88` is also narrower than a blanket rule — it prefers *outcome* assertions
  where transport exception types vary, and the red IT wants HTTP 400, not a catchable type. Deferred
  with an explicit re-entry trigger (§8).
- *Codex's five-field catch-all fingerprint* for Change 2: rejected as over-specified.

**Superseded by the security review — both my position and Codex's:**
- *Hint-key placement.* I argued for reusing the public `VERTX_STATUS_CODE_KEY` with a rewritten
  javadoc; Codex wanted a **second** package-private key alongside it. The review's third position is
  better than both: the key has no consumer at all (F19) and the project is unreleased, so it is
  **deleted from the public SPI** and moves wholly into rest-jaxrs as package-private
  `VertxFailureStatus.KEY` (§4). One key, zero public surface, no javadoc to broaden.
- *Body policy.* I froze **rewrite-derived / preserve-authored**. That was wrong on security grounds,
  not style: `JwtClaimsValidatorContributor` feeds arbitrary application exceptions into
  `ctx.fail(401, e)` and `RestModule.java:272` maps `IllegalArgumentException` with `ex.getMessage()`,
  so "preserving an authored detail" would have published whatever a tenant-binding or revocation check
  threw — a disclosure this change itself would have introduced. §4 now clears `detail`
  **unconditionally**, matching `OpenApiContractValidationStrategy`'s existing `SANITIZED_MESSAGE`
  precedent.
- *Slice boundary.* I had split "preserve the 4xx" from "sanitize the body" into two slices. That is
  unbuildable and unsafe: the intermediate commit would emit `{"title":"Bad Request","status":401,
  "detail":"bad token"}` — the corrected status carrying the leaked message — and it would delete a
  constant that `ErrorPipelineTest` still uses in seven places. They are **one** slice (S4).

**Correction the architect made to me:** my F12 premise ("`of()` sets both title and detail from its
arguments") was wrong; `of()` derives `title` from the status. F12 above is corrected accordingly.

## 4. Contract Appendix (frozen)

**Classification: small but source-breaking pre-release cleanup.** It removes one public SPI
constant — source-breaking for any external caller, which is acceptable only because the project is
unreleased (`0.1.0-SNAPSHOT`) and F19 found no consumer. Four modules touched. Class Inventory below (short enough to inline); it lists new and
visibility-changed types — the modified-but-unchanged-shape types (`JaxRsRouterMount`, `ErrorPipeline`,
`ErrorPipelineTest`, `MultipartPartCountLimitIT`, `MultipartBodies`) are in §6's manifest instead.

| Module | Package | Type | Kind | Visibility |
|---|---|---|---|---|
| rest-jaxrs | `dev.vertique.rest.jaxrs` | `VertxFailureStatus` | class | **Internal** (new, package-private) |
| rest-jaxrs | `dev.vertique.rest.jaxrs` | `VertxFailureStatusPreservationIT` | class | Test-fixture (new) |
| rest-auth-jwt | `dev.vertique.rest.auth.jwt` | `JwtClaimsRejectionStatusIT` | class | Test-fixture (new; needs a test-scoped `vertique-rest-jaxrs` dep) |
| rest-core | `dev.vertique.rest.core.interceptor` | `RequestInterceptor` | interface | API — **loses** one constant |

```java
// REMOVED from the public SPI: vertique-rest-core
//   dev.vertique.rest.core.interceptor.RequestInterceptor#VERTX_STATUS_CODE_KEY
// It is an internal handoff between exactly two rest-jaxrs classes (JaxRsRouterMount writes,
// ErrorPipeline reads). F19 found no consumer in this repo or in vertique-enterprise, and the
// project is unreleased — so rather than broaden a public constant nobody consumes, it moves to:

// vertique-rest-jaxrs: dev.vertique.rest.jaxrs.VertxFailureStatus   (package-private)
final class VertxFailureStatus {
    /**
     * Routing-context data key carrying the terminally observed authoritative Vert.x failure status:
     * either an unwrapped {@link io.vertx.ext.web.handler.HttpException}'s status, or a 4xx the
     * Vert.x layer set alongside a non-{@code HttpException} cause. A non-{@code HttpException} 5xx
     * is deliberately NOT carried — {@code RoutingContext.fail(Throwable)} synthesises a 500 that
     * cannot be distinguished from a deliberate {@code fail(500, cause)}.
     *
     * <p>Written only in the terminal router-level failure handler, so no reroute can observe it.
     */
    static final String KEY = "dev.vertique.rest.vertxStatusCode";   // value unchanged
}
```

**Sanitized-body contract (frozen).** When the Vert.x hint changes the mapped status:

```
status  := hint
title   := ProblemDetail.titleForStatus(hint)      // always re-derived
detail  := omitted                                 // always cleared, never carried over
```

`detail` is cleared **unconditionally** — not "when it looks derived". This is a security rule, not a
tidiness one, and it reverses my earlier "preserve authored detail" design:
`JwtClaimsValidatorContributor.java:111` passes *arbitrary application exceptions* into
`ctx.fail(401, e)`, and `RestModule.java:272` maps `IllegalArgumentException` with `ex.getMessage()`.
Preserving an authored detail would therefore publish whatever a tenant-binding, revocation, or custom
claims check happened to throw — an information-disclosure regression introduced by this very change.
It mirrors `OpenApiContractValidationStrategy`'s existing `SANITIZED_MESSAGE` rule (`:337, :331-353`),
which discards the third-party message and keeps the cause for logs only.
An application that *wants* a specific detail registers its own `ExceptionMapper`, which outranks the
hint and never reaches this fallback.

**Title-derivation baseline, so the executor need not choose:** the entity's own
`ProblemDetail.status()` when non-null, else `response.getStatus()`.

**Behavioral contract — the status precedence chain** (ADR-0205 records it):

```
application-contributed ExceptionMapper registered for a type more specific than Throwable
  > explicit Vert.x 4xx failure status
  > framework default mapping (DefaultExceptionMapper)
  > Throwable catch-all (500)
```

**Consumer-visible behavior changes — decided here, not at implementation time:**

| Path | Before | After |
|---|---|---|
| multipart parts > `maxFormFields` | 500 | **400** |
| malformed multipart / oversized single form field (`DecoderException`) | 500 | **400** |
| `ctx.fail(401, e)` from `JwtClaimsValidatorContributor` (F15) | 500 | **401** |
| any `ctx.fail(4xx, cause)`, cause not `HttpException` | mapper's status | **the 4xx** |
| `ctx.fail(Throwable)` / `ctx.fail(500, cause)` | mapper's status | **unchanged** |
| existing `HttpException` 401/403 | status right, body incoherent (`title`/`detail` say "Internal Server Error") | status right, **`title` coherent and `detail` omitted** |

## 5. Slice plan

Executed in this order; the order is prescriptive.

**S1 — persist this plan.** `sources/vertique/docs/plans/fix-issue-220-multipart-part-count.md`.
Commit: `docs: add implementation plan for Vert.x failure-status preservation`. Tier: routine.

> **Gate after S1 — not a slice.** Re-run `plan-linter` on the *persisted* file. S2 may not begin on a
> FAIL without an explicit per-item user waiver (`workflow.md` step 2, second gate). Also delete the
> predecessor `~/.claude/plans/tingly-hatching-plum.md` here, so exactly one plan is live.

**S2 — make the probe IT able to see regressions (test-only).** Tier: routine.
Rewire `MultipartPartCountLimitIT`'s harness to `RestModule.defaultExceptionMapper()` (F10/F20).
Red-test spec — no new test methods; the existing five must behave as:
`manySmallFileParts…` / `manySmallTextParts…` / `mixedTextAndFileParts…` still **fail** on
`expected: <400> but was: <500>` (proving the rewire did not accidentally fix anything);
`partsAtConfiguredLimitAccepted` and `rejectedRequestLeavesNoSpooledFiles` still **pass**.
Plus two new tests:
- `oversizedSingleFormFieldRejectedAsBadRequest` — GIVEN a multipart body with one text part whose value
  exceeds `http.maxFormAttributeSize` (8192), WHEN posted, THEN 400. Today **red** (500), via
  `TooLongFormFieldException` → the same `DecoderException` branch. This proves F16's "whole decoder
  family" claim rather than asserting it.
- `mixedTextAndFilePartsAtLimitAccepted` — GIVEN 200 text + **56** file parts (= 256), WHEN posted,
  THEN 200 and 56 bound uploads. Today **green**. Paired with the existing 57+200 (= 257) rejection,
  this *demonstrates* the shared counter's exact boundary instead of inferring it from one
  above-the-line case — a single rejection above the boundary is consistent with several per-kind
  counting schemes; the matched pair is not.
S2 also settles two undeclared properties of the already-committed test code, so they stop being
silent scope:
- **The cleanup test's `Thread.sleep` quiescence poll** (`MultipartPartCountLimitIT.java:236`) is
  *retained*, with an inline justification. It is not a wall-clock wait for a timer: the server keeps
  draining the body after the response is written, so a naive "count == 0" check would read
  "not yet written" as "cleaned up" — a test that cannot fail. The poll is bounded (10 s cap, 500 ms
  settle window) and exits as soon as the count stabilizes; measured settle is ~26 ms.
- **The class `@Timeout` is 30 s, above `testing.md`'s 20 s default.** Justified in a class-level
  comment: each of the seven tests starts and stops its own server, and the cleanup test may hold up to
  its 10 s quiescence cap. Left at 30 s deliberately rather than tightened into a CI-load flake.

Commit: `test(rest-validation): wire the part-count IT to the framework's real exception defaults`.

**S3 — red proof for the seam.** Tier: critical.
New `VertxFailureStatusPreservationIT` (rest-jaxrs), every case wired against
`RestModule.defaultExceptionMapper()`. Given a mounted route whose handler fails the context in a
stated way, when the request is sent, then:

| test | given | expect | today |
|---|---|---|---|
| `vertxFourHundredWithCausePreserved` | `ctx.fail(400, new IllegalStateException("x"))` | 400 | **red** (500) |
| `vertxUnauthorizedWithCauseIsSanitized` | `ctx.fail(401, new IllegalArgumentException("bad token"))` | 401, `title` `"Unauthorized"`, **no `detail`**, and `"bad token"` absent from the body | **red** (500) |
| `userMapperOutranksVertxStatus` | `ctx.fail(400, new Marker())` + app `ExceptionMapper<Marker>`→422 | 422 | green (characterization) |
| `bareThrowableKeepsMapperStatus` | `ctx.fail(new UnavailableException("down"))` | 503 | green (characterization) |
| `explicitFiveHundredNeverOverrides` | `ctx.fail(500, new IllegalArgumentException("y"))` | 400 (mapper wins) | green (characterization) |
| `belowFourHundredIsNotCaptured` | `ctx.fail(200, new RuntimeException())` | 500 | green (characterization) |

**No reroute test.** An earlier draft had `rerouteLeavesNoStaleHint`; it was **vacuous** and is dropped.
The hint is written only in the terminal router-level failure handler, so a "fail → reroute → success"
sequence reroutes *before* the production writer stores anything — the test would pass while proving
nothing. With the key package-private and terminal-only (§4) there is no stale-hint surface to pin.

**Plus a direct JWT proof** — `JwtClaimsRejectionStatusIT` in `vertique-rest-auth-jwt`, mount-based so
it actually reaches `handleFailure` (unlike `ActionOnlyRouteClaimsValidatorIT`, which builds its router
without `JaxRsRouterMount`). GIVEN a `JwtClaimsValidator` that throws
`new IllegalArgumentException("tenant 4711 is not permitted")` on an otherwise valid token, WHEN a
request carries that token, THEN: status **401**; body `application/problem+json` with
`status == 401` and `title == "Unauthorized"`; and **the validator's message does not appear anywhere
in the response body**.

*Current state, stated precisely:* status and title are **red** (it renders 500 / "Internal Server
Error"); the message-absence assertion is **green characterization** today, because the catch-all
hardcodes `"Internal Server Error"` rather than echoing `getMessage()`. It is asserted anyway — it is
exactly the property S4's status change would otherwise destroy, and the actual *red* sanitization
proof lives in `fallbackClearsAuthoredDetailOnStatusOverride`.

**Two prerequisites, frozen so the implementer decides nothing:**
- `vertique-rest-auth-jwt/pom.xml` has **no** dependency on `vertique-rest-jaxrs` (verified), so S3
  adds one at **`<scope>test</scope>`**. That module already carries test-only
  `vertx-web-openapi-router` and `vertique-config-core`, so this matches existing practice and adds no
  production coupling.
- The mount is built with the **same `JaxRsRouterMount.Factory` harness** used by
  `MultipartFilePartValidationIT` and `MultipartPartCountLimitIT`, wired against
  `RestModule.defaultExceptionMapper()` (F10/F20) — not a new fixture pattern.

Also three `ErrorPipelineTest` unit cases for the body contract, written against the **still-public**
key (it is deleted in S4, so these compile here and get repointed there):

| test | given | then | today |
|---|---|---|---|
| `fallbackRewritesTitleOnStatusOverride` | catch-all 500 body, hint 400 | `title` == `"Bad Request"` | **red** (`"Internal Server Error"`) |
| `fallbackClearsDetailOnStatusOverride` | catch-all 500 body, hint 400 | `detail` absent | **red** (`"Internal Server Error"`) |
| `fallbackClearsAuthoredDetailOnStatusOverride` | `ProblemDetail.of(400, "tenant 4711 is not permitted")`, hint 401 | `detail` **absent**, `title` == `"Unauthorized"` | **red** on both |

Commit: `test(rest-jaxrs): add failing tests for Vert.x failure-status preservation`.

**S4 — green: preserve the 4xx *and* sanitize the body, in one commit.** Tier: critical. *Code repo.*

> **Why this is one slice and not two.** An earlier draft split the status fix from the body fix. That
> split is both unbuildable and unsafe. Unbuildable: deleting the public constant while
> `ErrorPipelineTest` still references it in seven places breaks compilation at that commit. Unsafe:
> the intermediate state emits `{"title":"Bad Request","status":401,"detail":"bad token"}` — the
> corrected status *carrying* the message the whole sanitization exists to suppress. The status flip
> and the sanitization are one semantic change and land together.

Four things, one commit:
1. **Third branch in `handleFailure`** — when `cause != null`, the cause is not an `HttpException`, and
   `400 <= ctx.statusCode() < 500`, stash `ctx.statusCode()`. Keep the raw cause.
2. **Internalize the key (§4)** — add package-private `VertxFailureStatus.KEY` in rest-jaxrs; repoint
   `JaxRsRouterMount`, `ErrorPipeline`, and **all seven `ErrorPipelineTest` uses** (`:64, 78, 102, 128,
   155, 167, 191`) plus the three new S3 cases; delete `RequestInterceptor.VERTX_STATUS_CODE_KEY`.
3. **Sanitized body** — in `applyVertxStatusCodeFallback`, inside the existing `instanceof
   ProblemDetail` branch (the non-`ProblemDetail` path is pinned at `ErrorPipelineTest.java:176-200`):
   set the status to the hint, re-derive `title` from it, clear `detail` unconditionally. Baseline for
   the original status is the entity's `ProblemDetail.status()` when non-null, else
   `response.getStatus()`.
4. **Docs in the same commit** — F17's wrong 413 claim (`JaxRsRouterMount.java:384-385`); the
   `ErrorPipeline.java:158-168` drift ("produced a 500" is narrower than the tested behavior);
   `vertique-rest-jaxrs` `module.md:370-374` (both the `HttpException`-only scoping *and* what the
   fallback replaces); `vertique-rest-core` `module.md:705, :736` (the constant is **removed** from the
   public artifact contract, not merely re-described); and one sentence in `vertique-rest-auth-jwt`
   `module.md` stating that a claims-validator rejection returns 401 with no `detail`.

Turns **everything** green: S2's four, all of S3's reds, and `JwtClaimsRejectionStatusIT`.
Commit: `fix(rest-jaxrs): preserve and sanitize a Vert.x 4xx set alongside a non-HttpException cause`.

**S5 — remove the persisted plan.** Tier: routine. *Code repo.*
Delete `docs/plans/fix-issue-220-multipart-part-count.md` in the PR's final docs commit, per
`planning.md` § Plan persistence ("its content lives on in git history, and `main` stays free of stale
plans"). No PRD backs this work, so the PRD-archival flow does not apply.
In-slice proof: `git diff main...HEAD --name-only` shows no file under `docs/plans/`.
Commit: `docs: remove implementation plan for Vert.x failure-status preservation`.

**S6 — ADR + maintainer docs + source pin (governance repo, own PR).** Tier: routine.
`adr/product/0205-vertx-failure-status-precedence.md` plus `adr/product/README.md`'s index row and
next-number bump to 0206. The ADR records **six** decisions, not one: the precedence chain (§4); the
strict 4xx guard and why the range is a provenance *proxy* with its falsifier (§7); that a
non-`HttpException` 5xx is deliberately not preserved; that the raw cause is kept rather than wrapped,
so application `ExceptionMapper`s still match; that the hint key is **internalized** to rest-jaxrs
rather than broadened as public SPI (F19 — no consumer, project unreleased); and the **sanitized-body
rule** — when the hint changes the status, `title` is re-derived and `detail` is cleared
unconditionally, because the cause may be an arbitrary application exception whose message must not
reach the client. The ADR also records that the no-stale-hint property rests on Vert.x *implementation*
behavior (`reroute()` retains `data()`), so a future upgrade has something to re-check.
Also updates the governance maintainer doc `docs/vertique-rest-jaxrs.md`, which the change invalidates
in three specific places: `:269-273` describes the fallback scope S4 broadens; `:277` pins "The
catch-all returns the fixed detail `\"Internal Server Error\"`" — precisely the field S4 clears; and
`:440`'s `## Related ADRs` section is where ADR-0205's textual traceability belongs (per `CLAUDE.md`,
`docs/<artifactId>.md` owns that, and packaged `module.md` must not).
**This is a separate commit in a different git repository** — the governance repo tracks the code repo
as a submodule, so these files cannot ride S4's commit.

**S6 is a governance PR, not a docs-only fast-forward.** The governance repo pins `sources/vertique`
at an exact SHA, and once the code PR merges that gitlink must advance to the merge commit — otherwise
the governance tree still points at a `sources/vertique` without the fix, and ADR-0205 documents
behavior the pinned code does not have. A gitlink bump is **not** a documentation file, so
`git-workflow.md` § Exception does not apply: S6 goes through a PR like any other change.

**Ordering:** prepare the commit after S4, hold it on a governance branch, and open/merge that PR only
**after** the code PR is CI-green and merged — so the gitlink can point at a real merge commit.

In-slice proof, repo-appropriate (the governance repo has no reactor and no `scripts/`):
`adr/product/README.md`'s index row exists for 0205 and its next-number marker reads 0206; every ADR
reference added to **both** maintainer docs — `docs/vertique-rest-jaxrs.md` *and*
`docs/vertique-rest-auth-jwt.md` — resolves to a file under `adr/product/`; and
`git -C sources/vertique rev-parse HEAD` equals the code PR's merge commit.
`./scripts/verify-module-docs.sh` and the release-equivalent javadoc build belong to the code-repo
slice (S4), where they can actually run.
Commits: `docs(rest-jaxrs): record the failure-status precedence chain (ADR-0205)` and
`chore: advance the vertique pin past the failure-status fix`.

## 6. Artifact manifest

Two git repositories are involved, so every entry carries its repo. **Code** = `sources/vertique`
(worked in the worktree, where a `sources/vertique/X` path is `<worktree>/X`). **Gov** = the governance
repo `vertique-dev`. No commit may span both.

**New**

| Repo | Path | Slice |
|---|---|---|
| Code | `docs/plans/fix-issue-220-multipart-part-count.md` (transient — deleted in S5) | S1 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/VertxFailureStatus.java` (package-private hint key) | S4 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/VertxFailureStatusPreservationIT.java` | S3 |
| Code | `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtClaimsRejectionStatusIT.java` | S3 |
| Gov | `adr/product/0205-vertx-failure-status-precedence.md` — `adr/product/README.md:217` reads "Next ADR number: 0205", verified current; **re-check before writing**, parallel worktrees race this counter | S6 |

**Modified**

| Repo | Path | Change | Slice |
|---|---|---|---|
| Code | `vertique-rest/vertique-rest-validation/src/test/java/dev/vertique/rest/validation/MultipartPartCountLimitIT.java` | harness rewired to the real defaults; added oversized-form-field and mixed-at-limit tests | S2 |
| Code | `vertique-rest/vertique-rest-validation/src/test/java/dev/vertique/rest/validation/MultipartBodies.java` | a builder for one oversized text part (the existing `parts()` emits only short values) | S2 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/JaxRsRouterMount.java` | third branch; F17 javadoc | S4 |
| Code | `vertique-rest/vertique-rest-core/src/main/java/dev/vertique/rest/core/interceptor/RequestInterceptor.java` | **removes** `VERTX_STATUS_CODE_KEY` — the declaration at `:91` and its javadoc usage example at `:88` (§4) | S4 |
| Code | `vertique-rest/vertique-rest-core/src/main/resources/META-INF/vertique/module.md` | `:705, :736` key semantics | S4 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md` | `:370-374` fallback scope and what it replaces | S4 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/ErrorPipeline.java` | sanitized-body repair; javadoc drift | S4 |
| Code | `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ErrorPipelineTest.java` | three red cases (S3), then repointed to the internal key (S4) | S3, S4 |
| Code | `vertique-rest/vertique-rest-auth-jwt/pom.xml` | adds test-scoped `dev.vertique:vertique-rest-jaxrs` so the mount-based auth IT compiles | S3 |
| Code | `vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md` | one sentence: 401 with no `detail` on a claims-validator rejection | S4 |
| Gov | `adr/product/README.md` | index row for 0205; next-number marker → 0206 | S6 |
| Gov | `sources/vertique` (submodule gitlink) | advanced to the code PR's merge commit — this is what makes S6 a PR rather than a docs-only fast-forward | S6 |
| Gov | `docs/vertique-rest-auth-jwt.md` | the `## Testing` table (heading `:255`, rows from `:259`) gains `JwtClaimsRejectionStatusIT`; ADR-0205 reference | S6 |
| Gov | `docs/vertique-rest-jaxrs.md` | `:269-273` fallback scope; `:277` the pinned `"Internal Server Error"` detail S4 clears; `:440` ADR-0205 traceability | S6 |

**Module documentation decisions (one per touched BOM-managed consumable)**
- `vertique-rest-jaxrs`: **Modified** in S4 — packaged `module.md:370-374` scopes the fallback to "a
  Vert.x `HttpException`"; that description broadens and the replaced-body behavior changes. Its
  governance maintainer doc `docs/vertique-rest-jaxrs.md` is **also** Modified, in S6 (different repo),
  for the fallback scope, the pinned catch-all detail, and ADR-0205 traceability.
- `vertique-rest-core`: **Modified** in S4 — `module.md:705, :736` document `VERTX_STATUS_CODE_KEY`,
  whose documented semantics change.
- `vertique-rest-validation`: **no documentation impact** — test-only change (S2); no application-facing
  API, config, wiring, behavior, or verification changes.
- `vertique-rest-auth-jwt`: **Modified** in S4. Its `module.md` already documents this path as **401**
  (`:378, :446, :531`), which the status fix makes *accurate* rather than aspirational — but the
  sanitized-body rule is new application-facing behavior, so one sentence is added stating that a
  claims-validator rejection returns 401 with **no `detail`**, and that an application wanting a
  specific detail must register its own `ExceptionMapper`. Decided here, not left to the executor.
  Its governance maintainer doc `docs/vertique-rest-auth-jwt.md` is also Modified, in S6: the
  `## Testing` table (heading `:255`, rows from `:259`) gains `JwtClaimsRejectionStatusIT`, plus an
  ADR-0205 reference.

**Deleted**

| Repo | What | Slice |
|---|---|---|
| Code | `RequestInterceptor.VERTX_STATUS_CODE_KEY` — the public SPI constant (§4); superseded by package-private `VertxFailureStatus.KEY` | S4 |
| Code | `docs/plans/fix-issue-220-multipart-part-count.md` — the transient persisted plan | S5 |
| — | `~/.claude/plans/tingly-hatching-plum.md` — the superseded predecessor (not tracked in either repo) | post-S1 gate |

**Deletion → replacement map**

| Removed | Replaced by |
|---|---|
| `RequestInterceptor.VERTX_STATUS_CODE_KEY` | `dev.vertique.rest.jaxrs.VertxFailureStatus.KEY` (package-private, same string value) |

**Mechanical completeness check** (a public symbol is removed, so `planning.md` requires one):

```
grep -rn "VERTX_STATUS_CODE_KEY" --include="*.java" --include="*.md" . \
  | grep -v /target/ | grep -v '^\./docs/plans/'
```
must return **exactly zero** hits after S4 — the identifier ceases to exist under every name. The
`ErrorPipelineTest` `@DisplayName` and comment strings that mention it (`:62, 76, 88, 90`) are reworded
in S4 along with the seven code uses, and `RequestInterceptor.java:88`'s javadoc usage example is
deleted with the constant at `:91`. So no "expected residue" needs excusing.

Stated as zero rather than "only hits under rest-jaxrs", so the check cannot pass while the migration is
half-done. The **one** exclusion is `docs/plans/` — this plan file itself is persisted into the code
repo in S1 and names the symbol ~10 times *because it documents the removal*; it is a transient
artifact deleted in S5, not part of the shipped tree. Excluding it is what makes the zero-hit demand
satisfiable at the moment it is specified to run (after S4, before S5).

Two readers lose access when the key goes package-private, both handled before S4:
- `ErrorPipelineTest` (same package as the new key) — repointed in S4.
- `MultipartPartCountLimitIT.FailureCapture` (`:315`, a different module) — **S2 drops that field from
  its diagnostic string**, which is safe because the fact it existed to settle (F1) is now recorded in
  this plan. S2 precedes S4, so no commit is left uncompilable.

## 7. Risks & edge cases

- **The 4xx guard is a provenance *proxy*, not provenance.** It infers "the Vert.x layer made a
  deliberate client-error decision" from the status range. Falsifier (from the debate): a supported
  path that calls `ctx.fail(4xx, cause)` while *requiring* the cause's mapper status to win. None found
  in framework code (§F14's audit enumerated every two-arg `fail`), but an application could
  introduce one — hence the ADR.
  Two further production sites newly reach the S4 branch and are **provably unobservable**:
  `JaxRsRouteRegistrar.java:796` and `ContentTypeValidationMiddleware.java:63` both do
  `ctx.fail(415, new NotSupportedException(...))`, whose cause already maps to 415 — so hint and mapped
  status agree and no response changes. Named here so the sweep is complete rather than silent.
- **Information disclosure — the risk this change could have introduced.** Making the JWT path answer
  401 instead of 500 also routes an *arbitrary application exception's message* into the response body,
  because `RestModule.java:272` maps `IllegalArgumentException` with `ex.getMessage()`. A tenant-binding
  or revocation validator's message would have become public. Mitigated by §4's unconditional
  `detail` clearing and proved by `JwtClaimsRejectionStatusIT`'s "message absent from the body"
  assertion. **This is why the fallback sanitizes rather than preserves** — an earlier draft of this
  plan had it backwards.
- **F15 now has direct coverage** — `JwtClaimsRejectionStatusIT` (S3), mount-based so it reaches
  `handleFailure`. It is no longer deferred: a consumer-visible authentication change cannot ship on
  mechanism-only proof.
- **`reroute()` clears `statusCode` and `failure` but not `data()`** (`RoutingContextImpl.java:407,418`).
  The hint is written **only** in the terminal router-level failure handler, so nothing can be rerouted
  *after* a write within one dispatch — there is no stale-hint surface, which is precisely why the
  reroute test was dropped as vacuous rather than kept for reassurance. Note this rests on Vert.x
  *implementation* behavior, not a published guarantee; it is restated in ADR-0205 so a future Vert.x
  upgrade has something to check against.
- **The cleanup poll's stabilization window is a heuristic.** If file creation were delayed beyond
  500 ms the poll could settle early and false-pass. Bounded by the 10 s cap and the measured ~26 ms
  settle; S2 also makes the poll use `System.nanoTime()` throughout (it currently mixes
  `currentTimeMillis()` for the window with `nanoTime()` for the deadline).
- **S4 changes an existing response body** for the 401/403 fallback path — `title` becomes coherent and `detail` disappears. Correct, and deliberately observable.
- **Not fixed by this change:** a non-`HttpException` **5xx** is still not preserved, because
  `fail(Throwable)` synthesises an indistinguishable 500. Recorded in the ADR as a known limitation.

## 8. Verification

- Per slice, complete and copyable:
  ```bash
  # S2 — the multipart IT
  ./mvnw -ntp -pl vertique-rest/vertique-rest-validation -am verify -Dtest=__NoSuchUnitTest__ -Dit.test=MultipartPartCountLimitIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
  ```
  ```bash
  # S3/S4 — the seam IT and the ErrorPipeline unit cases
  ./mvnw -ntp -pl vertique-rest/vertique-rest-jaxrs -am verify -Dtest=ErrorPipelineTest -Dit.test=VertxFailureStatusPreservationIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
  ```
  ```bash
  # S3/S4 — the auth IT, isolated
  ./mvnw -ntp -pl vertique-rest/vertique-rest-auth-jwt -am verify -Dtest=__NoSuchUnitTest__ -Dit.test=JwtClaimsRejectionStatusIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
  ```
  **Note:** naming an `*IT` class in `-Dtest` makes Surefire run it in the unit fork and abort before
  Failsafe — hence `-Dtest=__NoSuchUnitTest__` whenever an IT is isolated on its own.
- Regression sweep, mandatory before review — these are F14's exposed set:
  `WebValidationGateIT`, `MultipartFilePartValidationIT`, `FileVerifierRejectionIT`,
  `MagicBytesVerifierRouteIT`, `ProfiledBodyParseUnderGateIT`, `ErrorPipelineTest`,
  `ErrorPipelineInterceptorCharacterizationTest`.
- **50-run stress loop on the new IT before sign-off**, per `testing.md` § Async / Network IT
  Determinism ("5 local runs is not deterministic"). `VertxFailureStatusPreservationIT` is a new
  network IT, so three runs do **not** qualify it:
  ```
  for i in $(seq 50); do ./mvnw -ntp -pl vertique-rest/vertique-rest-jaxrs -am verify \
      -Dtest=__NoSuchUnitTest__ -Dit.test=VertxFailureStatusPreservationIT \
      -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false || break; done
  ```
  Same for `MultipartPartCountLimitIT` (S2) and `JwtClaimsRejectionStatusIT` (S3). **CI green is the
  gate, not local** — hence the draft-PR rule below.
- Auth regression sweep, because S4 changes an authentication status: the full
  `vertique-rest-auth-jwt` and `vertique-rest-security` suites, plus `ActionOnlyRouteClaimsValidatorIT`
  (which asserts 401 through a non-mount router and must stay unaffected).
- The PR opens as a **draft** (`gh pr create --draft`) because the change adds and modifies `*IT.java`,
  per `workflow.md` § PR Workflow for IT-Touching Changes; promote only after CI is green.
- Full `./mvnw -ntp clean verify` via `build-validator` (delete any stale `target/spotless-index`
  first — a cached index produced a false-green Spotless result earlier in this initiative).
- `./scripts/verify-module-docs.sh` (code repo).
- Release-equivalent javadoc over the touched modules — `./mvnw -ntp -Prelease javadoc:javadoc -pl
  vertique-rest/vertique-rest-core,vertique-rest/vertique-rest-jaxrs -am`. Required because
  `clean verify` **never runs javadoc** in this project (`maven-javadoc-plugin` is confined to the
  `release` profile), and S4 edits javadoc. A bare `javadoc:javadoc` is *stricter* than a release
  build and fails on pre-existing diagnostics — use `-Prelease`.
- **Acceptance walkthrough**, row by row against §4's consumer-visible table:

  | §4 row | Covering test | Slice |
  |---|---|---|
  | parts > `maxFormFields` → 400 | `manySmallFileParts…`, `manySmallTextParts…`, `mixedTextAndFileParts…` | S2 red → S4 green |
  | `DecoderException` family → 400 | `oversizedSingleFormFieldRejectedAsBadRequest` (`TooLongFormFieldException`) | S2 red → S4 green |
  | `ctx.fail(401, e)` from the JWT contributor → 401, no leaked message | `JwtClaimsRejectionStatusIT` (direct, mount-based) + `vertxUnauthorizedWithCauseIsSanitized` (mechanism) | S3 red → S4 green |
  | any `ctx.fail(4xx, cause)` → the 4xx | `vertxFourHundredWithCausePreserved` | S3 red → S4 green |
  | `ctx.fail(Throwable)` / `fail(500, cause)` unchanged | `bareThrowableKeepsMapperStatus`, `explicitFiveHundredNeverOverrides` | S3 (characterization, green throughout) |
  | `HttpException` 401/403 body now coherent **and sanitized** | `fallbackRewritesTitleOnStatusOverride`, `fallbackClearsDetailOnStatusOverride`, `fallbackClearsAuthoredDetailOnStatusOverride` | S3 red → S4 green |

  `ErrorDataDecoderException` (malformed multipart framing) is **not** separately tested — it shares the
  single `DecoderException` branch that `oversizedSingleFormFieldRejectedAsBadRequest` exercises. Stated
  so the coverage gap is explicit rather than implied by F16's "for free".

## 9. Out-of-scope & deferral routing

Each routes to a GitHub issue in `vertiquehq/vertique-dev` (no PRD backs this work):

| Deferred | Re-entry trigger |
|---|---|
| Typed framework exception for transport/decode failures | A consumer needs to *catch* the type or needs structured decode diagnostics, not just the HTTP result |
| Preserving a non-`HttpException` 5xx | Vert.x gains a way to distinguish an intentional 5xx from `fail(Throwable)`'s synthesised 500 |
| A failure-status *provenance* model replacing the 4xx range heuristic | A second legitimate case appears where the cause's mapper must outrank an explicit Vert.x 4xx |
| Making `hasSpecificMapper` see framework built-ins | Its own ADR — reverses a pinned, consumer-visible precedence |
| A test for the documented 413 path (F18) | Standalone gap; cheap once a body-limit harness exists |
| `ErrorDataDecoderException` (malformed multipart framing) coverage | Shares the tested `DecoderException` branch; file if that branch ever splits per exception type |
| `rest-core` has 7 public exception types and no `exception` package, violating the convention's three-type threshold | Pre-existing; a mechanical but wide refactor |
| Exposing `http.maxFormBufferedBytes` (unset, sits at Vert.x default 1024) | Measured evidence of a need. **Not** a #220 mitigation: Netty applies it only to bytes retained *undecoded* after parsing, so it bounds neither aggregate form size nor parser churn, and fully decoded parts may exceed it. The predecessor plan's F4 characterized this wrongly; nothing in this plan relies on it. |
