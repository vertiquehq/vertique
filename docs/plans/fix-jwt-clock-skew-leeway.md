# Implementation plan — `jwt.validation.clockSkewSeconds` reaches every `JWTAuth` construction path

Issue: [vertiquehq/vertique-dev#37](https://github.com/vertiquehq/vertique-dev/issues/37)
Branch: `fix/jwt-clock-skew-leeway` (code repo `vertiquehq/vertique`, submodule `sources/vertique`)
Worktree: `.claude/worktrees/issue-37-clock-skew`
Module: `vertique-rest/vertique-rest-auth-jwt` (+ javadoc-only touches in `vertique-rest-security`)

**Path convention used throughout.** Two repositories are touched. Paths under
*"Code repository"* are relative to the submodule root `sources/vertique`. Paths under
*"Governance repository"* are relative to `vertique-dev`. Every manifest path is written in full so
the executor's drift check (`git log <plan-commit>..main -- <paths>`) is runnable verbatim.

---

## 1. Context & goal

`jwt.validation.clockSkewSeconds` is parsed, typed, defaulted to 30, and carried through
`JwtAuthConfig` into `JwtBearerSecuritySchemeHandler` — and reaches Vert.x on only **2 of 7**
`JWTAuth` construction paths. An operator who sets it to tolerate issuer/service clock drift gets
silence: tokens near the expiry boundary are rejected as if the setting were absent, with no warning
and no error.

Goal: every framework-built `JWTAuth` applies the effective `clockSkewSeconds`, including across
JWKS refresh ticks, and a divergence between what the factory applied and what config says fails at
startup instead of silently.

**Why now, not deferred.** Leeway is *permissive*: once the inner `JWTAuth` rejects an expired
token, nothing downstream can un-reject it. There is no handler-layer backstop possible — now or
ever — so the value must be present at construction. That is also why the defect has no operational
mitigation.

**The boring alternative, and why it loses.** Add the four missing `JwtValidationConfig` overloads
and stop (the issue's literal suggestion). Rejected: it leaves the all-defaults case still broken
(§2 F2) and leaves the trap one method call away. See D1.

---

## 2. Pre-flight findings (verified against code and runtime, 2026-07-30)

Every fact below was established by reading this repository or by throwaway probes run against
Vert.x 5.1.2 — none is taken from the issue text or from a spec.

**F1 — The reported bug, confirmed and localised.** `clockSkewSeconds` reaches `JWTOptions` only via
private `JwtAuthFactory.buildJwtOptions`, called only from `createFromJwksContent(vertx, content,
config)` when `config != null`. `RefreshableJwtAuth.create` calls the 2-arg
`JwtAuthFactory.fromJwksAsync` at `RefreshableJwtAuth.java:121` **and again** at
`RefreshableJwtAuth.java:202` inside `onRefreshTick`. Fixing only the initial load would regress
leeway to 0 at the first key rotation.

**F2 — The bug is wider than the issue title: the all-defaults case is also broken.**
`JwtValidationConfig.clockSkewSeconds` is `@Builder.Default = 30` and the module documents 30 as the
default, but Vert.x receives **0** whenever no config is passed — which is every call site in this
repository today.

**F3 — Vert.x runtime semantics (probe 1).** `new JWTOptions().getLeeway() == 0`;
`isIgnoreExpiration() == false`; `JWTAuth` **does** reject an expired token by default
(`Invalid JWT token: token expired.`); `setLeeway(120)` accepts a token whose `exp` was 60 s in the
past. Leeway is in **seconds** and is load-bearing.

**F4 — Leeway covers `exp`, `nbf` *and* `iat` (probe 2).** With no leeway, tokens carrying
`nbf = now + 60` or `iat = now + 60` are both rejected; with `setLeeway(120)` both are accepted. This
matches `JwtValidationConfig.clockSkewSeconds`'s own javadoc. **Vert.x reports all three failures
with the same message, `"Invalid JWT token: token expired."`** — so tests must assert on the
authentication *outcome*, never on the message (`testing.md`, "Match outcomes, not exception types").

**F5 — `JWTAuth` exposes no options accessor.** The interface is `create` + two `generateToken`
overloads; `JWTAuthProviderImpl` is internal. So (a) proof must be **behavioral**, not
option-introspection, and (b) nothing downstream can *discover* whether leeway was applied — hence
attestation (S5) rather than inspection.

**F6 — Wrapping the `JWTAuth` is safe.** `JwtBearerSecuritySchemeHandler.java:210` calls
`JWTAuthHandler.create(jwtAuth)`. `javap -p -c` on `JWTAuthHandlerImpl` from `vertx-web-5.1.2.jar`
shows its only JWT-related `checkcast` is to the **interface** `io/vertx/ext/auth/jwt/JWTAuth`, never
to the impl class. A wrapper is safe through that call, and `RefreshableJwtAuth` is already such a
wrapper.

**F7 — Nothing in the suite would catch any of this.** The only expiry assertions in the module
(`JwtBearerSecuritySchemeHandlerTest`) feed a **mocked** `HttpException(401, new
RuntimeException("...token expired."))`. No test anywhere mints a genuinely expired token against a
real `JWTAuth`. The leeway 0 → 30 change would therefore pass the entire suite green and silent —
which is why S1 exists before S2.

**F8 — Blast radius is 3 main-source call sites**, all `fromSymmetricKey`, all in example
`AppModule`s acting as verifiers (`vertique-example-hello`, `-custom-response`, `-websocket`). Test
call sites use `fromSymmetricKey` as a **signer**. `RefreshableJwtAuth` is referenced only inside its
own module (2 main + 2 test files), so changing `fromJwksRefreshing`'s return type has zero external
callers.

**F9 — Adjacent defect: the refresh tick can block the event loop.** `fromJwksAsync` dispatches to
`executeBlocking` only for `http(s)`; classpath and filesystem locations fall through to
`Future.succeededFuture(fromJwks(...))`, which calls `vertx.fileSystem().readFileBlocking(...)` on
the calling thread. `onRefreshTick` runs on the event loop, so
`fromJwksRefreshing(vertx, "/etc/secrets/jwks.json", …)` blocks the event loop every tick.

**F10 — Adjacent defect: JDK `HttpClient` is leaked per fetch.** `fetchHttp` builds a new
`HttpClient` per call and never closes it. `HttpClient` is `AutoCloseable` on JDK 21. On a refreshing
provider this leaks selector/executor threads until GC.

**F11 — `JwtValidationConfig` has no validation at all** — no bounds anywhere. A negative
`clockSkewSeconds` is passed straight to `setLeeway`. Gap against this project's own canonical
config pattern (`.claude/rules/config.md`). **It is a Lombok `@Builder` class, not a record**
(`JwtValidationConfig.java:53–58`), so the bounds check cannot use record compact-constructor
syntax — see D8.

**F12 — `RefreshableJwtAuth.close()` has no production call site.** Repo-wide grep finds it only in
`RefreshableJwtAuthTest` and `RefreshableJwtAuthIT`. `fromJwksRefreshing` returns `Future<JWTAuth>`
via `.map(auth -> auth)`, erasing the type that declares `close()`.

**F13 — The `#73` tracker citation is wrong in 7 places.** `vertiquehq/vertique-dev#73` is
*"Avoid toString round-tripping in the collection multiplicity fallback"*; `vertiquehq/vertique` has
issues disabled, so no other tracker can be meant. Sites: `AuthModule.java:40,57`,
`SecurityPolicyEnforcer.java:84`, `AuthorizationDecisionPoint.java:32`,
`vertique-dev/docs/modules/vertique-rest-security.md:279`,
`vertique-dev/docs/adr/product/0064-typed-security-identity-model.md:141,226`. `vertique-rest-security`'s
`module.md` documents the behavior correctly **without** citing a number.

> Two further hits exist in `vertique-dev/docs/prd/product/archive/identity-001-security-identity.md`
> (lines 8, 614). **Deliberately not corrected:** an archived PRD is a point-in-time record of what
> was decided and believed then; rewriting it would falsify the record. The §10 completeness grep is
> scoped to exclude `docs/prd/product/archive/` for this reason.

**F14 — Corrected assumption.** `JwtClaimAuthorizationProvider.java:46–47` claims "The extracted
authorizations can be enforced via `@RolesAllowed` and `@Authorized(scopes = ...)`".
`VertxProviderDecisionPoint` keeps its `Set<AuthorizationProvider>` "for forward-compatibility" and
evaluates from `AuthorizationClaims` **without invoking the provider chain** (its own javadoc, lines
38–40). The javadoc is materially wrong about shipped behavior.

**F15 — No Dagger cycle blocks the documented idiom.** `JwtAuthModule.effectiveJwtAuthConfig`
depends only on `Optional<JwtAuthConfig>`, `@VertxConfig JsonObject`, and `ConfigParser` — none touch
`JWTAuth`. An application's own `@Provides JWTAuth` **can** inject `@JwtEffective JwtAuthConfig`,
making "one source of truth" reachable as a documented idiom.

---

## 3. Decisions the executor must not have to make

**D1 — API shape: defaults-delegation, not mandatory config.** The no-config overloads are kept and
delegate internally to the validating path with `JwtValidationConfig.builder().build()`. Callers
change nothing. *Rejected:* deleting the non-validating overloads — it makes omission structurally
impossible but charges every call site permanently, including test signers where a *validation*
config is semantically meaningless; the residual risk it removes is divergence, which D3 catches.
**User ruled on this.**

**D2 — Consumer-visible behavior change, accepted deliberately: leeway 0 → 30 on every defaulted
path.** 30 is the documented default; today's 0 is the bug. Safety argument: leeway is **permissive
only**, so no token accepted today becomes rejected — the change can only widen tolerance near the
`exp`/`nbf`/`iat` boundaries. Recorded in ADR-0206 and pinned by a test asserting all-defaults leeway
is 30, not 0 (S1). RFC 7519 §4.1.4 permits "some small leeway, usually no more than a few minutes".
**Do not cite FAPI 2.0** in the ADR — that claim surfaced in consultation unverified.

**D3 — Divergence fails at startup, absence stays silent.** Framework factories attest the config
they applied; `JwtAuthModule` compares it against `@JwtEffective JwtAuthConfig.validation()` and
throws `ConfigurationException` on mismatch. A `JWTAuth` that is **not** framework-built carries no
attestation and is accepted silently — a hand-rolled provider is the application's own business.
Attestation is **internal** (package-private interface): no public type, no change to any
application's `@Provides JWTAuth` signature. *Rejected:* a public `ConfiguredJwtAuth` binding type
(the consultation's opening position, withdrawn in round 2 — no failure mode was nameable that the
internal form misses, and both designs equally trust self-attestation).

**D4 — Issuer/audience warning surface is unchanged.** `buildJwtOptions` logs a WARN when
issuer/audience are unset. Routing every path through it would newly emit two WARN lines at startup
for the three example apps. Decision: `buildJwtOptions` takes an explicit
`warnOnMissingConstraints` flag; internal default-delegation passes `false`, caller-supplied config
passes `true`. Rationale: this change alters exactly **one** axis (leeway), which is what makes D2's
ADR crisp; the "issuer/audience unset" hazard is already a documented `module.md` mistake.

**D5 — Slice order is prescriptive: S0 → S8 as written.** Every commit compiles. This is why the
red-test slices S1/S3 contain **only** tests that compile against today's signatures; each test that
needs a *new* overload lives in the slice that adds it (S2/S4/S5/S6), written red-first inside that
slice and committed green. A red-only commit referencing a not-yet-existing method would be a
compile error, not a failing test, and would break bisectability.

**D6 — `fromJwksRefreshing` returns `Future<RefreshableJwtAuth>`.** Zero external callers (F8), and
it stops erasing the type that declares `close()` (F12). No deprecation path needed pre-1.0.

**D7 — The `AuthorizationProvider` no-op *behavior* is not decided here.** Only the wrong
documentation is corrected. Behavior routed to a new GitHub issue (§11).

**D8 — `JwtValidationConfig` bounds land in an explicit private all-args constructor.** The type is a
Lombok `@Builder` class, not a record (F11), so there is no compact constructor. Declare a private
all-args constructor carrying the bounds check; class-level `@Builder` reuses it instead of
generating one, `@Builder.Default` still supplies 30, and `@Jacksonized` deserialization routes
through the builder into the same constructor. No annotation is moved.

---

## 4. Contract Appendix (frozen)

Public surface, fixed before implementation. Existing signatures unchanged unless listed as *changed*.

### `JwtAuthFactory` — added

```java
/**
 * Creates a {@link JWTAuth} from a symmetric (HMAC) key, applying the supplied validation
 * constraints (issuer, audience, and {@code exp}/{@code nbf}/{@code iat} leeway) as
 * {@link JWTOptions}.
 *
 * @param vertx     the Vert.x instance
 * @param algorithm the HMAC algorithm (e.g. "HS256")
 * @param secret    the symmetric key
 * @param config    the validation constraints to apply; must not be {@code null}
 * @return a configured JWTAuth instance that attests {@code config}
 */
public static JWTAuth fromSymmetricKey(Vertx vertx, String algorithm, String secret, JwtValidationConfig config);

/** As {@link #fromPublicKey(Vertx, String, String)}, applying {@code config} as {@link JWTOptions}. */
public static JWTAuth fromPublicKey(Vertx vertx, String algorithm, String pem, JwtValidationConfig config);

/**
 * As {@link #fromJwksRefreshing(Vertx, String, Duration)}, applying {@code config} to the initial
 * key set and to every key set fetched by a subsequent refresh tick.
 */
public static Future<RefreshableJwtAuth> fromJwksRefreshing(
        Vertx vertx, String location, Duration refreshInterval, JwtValidationConfig config);
```

### `JwtAuthFactory` — changed

```java
// Return type only: was Future<JWTAuth>. Zero external call sites (F8).
public static Future<RefreshableJwtAuth> fromJwksRefreshing(Vertx vertx, String location, Duration refreshInterval);
```

Behavior change on all five pre-existing no-config operations — `fromJwks(Vertx, String)`,
`fromJwksAsync(Vertx, String)`, `fromJwksRefreshing(Vertx, String, Duration)`,
`fromSymmetricKey(Vertx, String, String)`, `fromPublicKey(Vertx, String, String)`: each now applies
`JwtValidationConfig.builder().build()`, i.e. `setLeeway(30)`, issuer/audience unset, no new WARN
(D4). Signatures otherwise unchanged.

### `RefreshableJwtAuth` — added

```java
/**
 * As {@link #create(Vertx, String, Duration)}, applying {@code config} to the initial key set and
 * to every key set fetched by a subsequent refresh tick.
 *
 * @param config the validation constraints to apply; must not be {@code null}
 */
public static Future<RefreshableJwtAuth> create(
        Vertx vertx, String jwksLocation, Duration refreshInterval, JwtValidationConfig config);

/**
 * Returns the validation constraints applied to this instance's delegate, including every
 * refreshed delegate. Public by interface rule (implements the package-private
 * {@code ValidationAttested}); the interface type itself is not exported.
 *
 * @return the applied validation config; never {@code null}
 */
public JwtValidationConfig appliedValidation();
```

Invariant: the applied `JwtValidationConfig` is immutable for the instance's lifetime and is
re-applied to every refreshed delegate. `create(Vertx, String, Duration)` delegates with
`JwtValidationConfig.builder().build()`.

### `JwtValidationConfig` — added (F11, D8)

```java
/** Maximum permitted clock skew, in seconds. */
public static final int MAX_CLOCK_SKEW_SECONDS = 300;

/**
 * @throws dev.vertique.core.exception.ConfigurationException if {@code clockSkewSeconds} is
 *         negative or greater than {@link #MAX_CLOCK_SKEW_SECONDS}
 */
private JwtValidationConfig(String issuer, List<String> audience, int clockSkewSeconds);
```

Upper-bound rationale (recorded in ADR-0206): RFC 7519 §4.1.4's "no more than a few minutes"; above
300 s a misconfiguration is likelier than an intent.

### Internal — no public surface

```java
/** Attests the JwtValidationConfig a framework factory applied to a JWTAuth. */
interface ValidationAttested { JwtValidationConfig appliedValidation(); }

/** Delegating JWTAuth carrying its applied validation config. */
final class AttestedJwtAuth implements JWTAuth, ValidationAttested { … }
```

`AttestedJwtAuth` must delegate **all three** `JWTAuth` methods — `authenticate(Credentials)`,
`generateToken(JsonObject)`, `generateToken(JsonObject, JWTOptions)`. `RefreshableJwtAuth`
additionally implements `ValidationAttested`.

---

## 5. Class Inventory

| Module | Package | Type | Kind | Visibility |
|---|---|---|---|---|
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtAuthFactory` | final class | **API** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `RefreshableJwtAuth` | final class | **API** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtValidationConfig` | class (`@Builder`) | **API** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtAuthModule` | abstract class | **API** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtBearerSecuritySchemeHandler` | class | **API** (javadoc only) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtClaimAuthorizationProvider` | class | **API** (javadoc only) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `ValidationAttested` | interface | **Internal** (new) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `AttestedJwtAuth` | final class | **Internal** (new) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtValidationLeewayTest` | class | **Test-fixture** (new) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtAuthModuleProvenanceTest` | class | **Test-fixture** (new) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtValidationConfigBoundsTest` | class | **Test-fixture** (new) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `RefreshableJwtAuthTest` | class | **Test-fixture** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `RefreshableJwtAuthIT` | class | **Test-fixture** (modified) |
| `vertique-rest-auth-jwt` | `dev.vertique.rest.auth.jwt` | `JwtAuthFactoryTest` | class | **Test-fixture** (modified) |
| `vertique-rest-security` | `dev.vertique.rest.security` | `AuthModule` | abstract class | **API** (javadoc only) |
| `vertique-rest-security` | `dev.vertique.rest.security` | `SecurityPolicyEnforcer` | class | **API** (javadoc only) |
| `vertique-rest-security` | `dev.vertique.rest.security` | `AuthorizationDecisionPoint` | interface | **API** (javadoc only) |

No type is deleted, so no deletion → replacement map is required.

---

## 6. Slice plan

Each slice is red → green → one commit. Risk tier selects the implementer model; the executor does
**not** re-tier (escalation on twice-failure only). Per D5, tests needing a new overload live in the
slice that adds it.

### S0 — Persist the plan · tier `routine`

Create the worktree; write this file to `docs/plans/fix-jwt-clock-skew-leeway.md`.
Commit: `docs: add implementation plan for jwt clock-skew leeway`

### S1 — Red: leeway proof against today's signatures · tier `routine`

New `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtValidationLeewayTest.java`.
Every test compiles against existing signatures (D5) and fails today (F7). Mint tokens with explicit
`exp`/`nbf` claims; assert on the `authenticate(...)` outcome, never on `JWTOptions` (F5) and never
on the failure message (F4).

| Test | Given | When | Then |
|---|---|---|---|
| `shouldApplyDocumentedDefaultLeewayOnSymmetricKeyPath` | token `exp` = now − 10 s | `fromSymmetricKey(vertx,"HS256",secret)` authenticates it | **succeeds** — default 30 covers 10 s (RED: leeway 0 today) |
| `shouldRejectTokenBeyondDefaultLeeway` | token `exp` = now − 120 s | same no-config path | **fails** — pins the far edge so the delegation cannot become "ignore expiry" |
| `shouldApplyDocumentedDefaultLeewayOnJwksPath` | token `exp` = now − 10 s | `fromJwks(vertx,"classpath:test-jwks.json")` | **succeeds** (RED) |
| `shouldApplyDefaultLeewayToNotBeforeClaim` | token `nbf` = now + 10 s | no-config symmetric path | **succeeds** within 30 s skew (RED) |
| `shouldApplyDefaultLeewayToIssuedAtClaim` | token `iat` = now + 10 s | no-config symmetric path | **succeeds** within 30 s skew (RED) |

Commit: `test(rest-auth-jwt): add failing tests for clock-skew leeway on default construction paths`

### S2 — Green: defaults-delegation in `JwtAuthFactory` · tier `critical`

Split `buildJwtOptions(JwtValidationConfig, boolean warnOnMissingConstraints)` per D4; route
`createFromJwksContent(vertx, content)` through a default config instead of `null`; add the two new
key-based overloads from §4; make the three no-config key/JWKS operations delegate.

Red-first inside this slice, added to `JwtValidationLeewayTest` immediately before the overloads
exist:

| Test | Given | When | Then |
|---|---|---|---|
| `shouldApplyExplicitLeewayOnSymmetricKeyPath` | `clockSkewSeconds = 300`, token `exp` = now − 120 s | `fromSymmetricKey(vertx,"HS256",secret,config)` | **succeeds** |
| `shouldApplyExplicitLeewayOnPublicKeyPath` | `clockSkewSeconds = 300`, token `exp` = now − 120 s, RS256 keypair | `fromPublicKey(vertx,"RS256",pem,config)` | **succeeds** |

S1's five tests turn green here.
Commit: `fix(rest-auth-jwt): apply configured clock skew on every JWTAuth construction path`

### S3 — Regression guard: refreshing-path default leeway · tier `routine`

**Amended after S2 — see Amendment A1. These tests are green on arrival, not red.**
S2 made the 2-arg `fromJwksAsync` apply the default config, and `RefreshableJwtAuth` calls exactly
that at both fetch sites, so the *default*-leeway half of the reported bug is already closed by S2.
The tests below still land: without them, a later change to `onRefreshTick` could silently revert the
refresh path to leeway 0 and nothing would catch it.

| Test | File | Given | When | Then |
|---|---|---|---|---|
| `shouldApplyDefaultLeewayOnRefreshingPath` | `RefreshableJwtAuthTest` | classpath JWKS, token `exp` = now − 10 s | `create(vertx, loc, Duration.ofMinutes(5))` | **succeeds** (green on arrival; guards S2) |
| `shouldRetainDefaultLeewayAfterRefreshTick` | `RefreshableJwtAuthIT` | WireMock JWKS, 200 ms interval; gate until `countRequestsMatching(...)` ≥ 2 | authenticate token `exp` = now − 10 s | **succeeds** — guards `onRefreshTick` (green on arrival) |

**Prove they can fail.** A guard test that has never been red is worth little (`tests-that-cannot-fail`).
Before committing, temporarily revert `createFromJwksContent`'s defaulted call site to pass a
`clockSkewSeconds(0)` config, confirm both tests go red, then restore. Record the result in the commit
body. Do not ship the temporary edit.

Both compile today (3-arg `create` exists). ITs reuse the existing static WireMock harness in
`RefreshableJwtAuthIT`; gate on request count, never on a bare sleep.
Commit: `test(rest-auth-jwt): guard default leeway across JWKS refresh ticks`

### S4 — Green: `RefreshableJwtAuth` carries the config · tier `critical`

Add the 4-arg `create`; store `validation` as a `final` field; use the 3-arg `fromJwksAsync` at
**both** `RefreshableJwtAuth.java:121` and `:202`; add the 4-arg `fromJwksRefreshing`; change both
`fromJwksRefreshing` return types to `Future<RefreshableJwtAuth>` (D6).

Red-first inside this slice:

| Test | File | Given | When | Then |
|---|---|---|---|---|
| `shouldRetainExplicitLeewayAfterRefreshTick` | `RefreshableJwtAuthIT` | `clockSkewSeconds = 300`, token `exp` = now − 120 s, tick fired (count ≥ 2) | `create(vertx, url, 200 ms, config)` authenticates | **succeeds** |
| `shouldReturnRefreshableTypeFromFactory` | `RefreshableJwtAuthTest` | classpath JWKS | `fromJwksRefreshing(vertx, loc, interval)` | future's value is assignable to `RefreshableJwtAuth`, so `close()` is reachable |

S3's two tests turn green here.
Commit: `fix(rest-auth-jwt): keep clock-skew leeway across JWKS refresh ticks`

### S5 — Attestation + fail-fast on divergence · tier `critical`

Red-first, then green in one slice (the check and its callers are introduced together):

| Test | File | Given | When | Then |
|---|---|---|---|---|
| `shouldFailStartupWhenAppliedLeewayDivergesFromConfig` | new `JwtAuthModuleProvenanceTest` | attested `clockSkewSeconds = 30`, effective config `90` | the provenance check runs | throws `ConfigurationException` naming both values and the path `jwt.validation.clockSkewSeconds` |
| `shouldAcceptMatchingAppliedValidation` | same | attested `30`, effective config `30` | the check runs | returns normally, nothing logged |
| `shouldAcceptUnattestedJwtAuthSilently` | same | a hand-rolled `JWTAuth` implementing no attestation | the check runs | returns normally — no throw, no warning |
| `shouldRejectNegativeClockSkew` | new `JwtValidationConfigBoundsTest` | `clockSkewSeconds = -1` | `JwtValidationConfig.builder()…build()` | throws `ConfigurationException` |
| `shouldRejectClockSkewAboveMaximum` | same | `clockSkewSeconds = 301` | build | throws `ConfigurationException` |
| `shouldAcceptClockSkewAtMaximum` | same | `clockSkewSeconds = 300` | build | succeeds, `clockSkewSeconds() == 300` |

Green: `ValidationAttested` + `AttestedJwtAuth` (delegating all three `JWTAuth` methods, §4); every
`JwtAuthFactory` return wrapped; `RefreshableJwtAuth implements ValidationAttested`; the check
invoked from `JwtAuthModule.jwtBearerSchemeHandler`, which already receives both `JWTAuth` and
`@JwtEffective JwtAuthConfig`. Plus the D8 bounds constructor.

Real-generated-component proof: the three example apps build a real `DaggerAppComponent` and their
existing ITs boot it, so a misfiring check fails `verify` — no hand-written Dagger fixture needed.
Commit: `feat(rest-auth-jwt): fail fast when applied clock skew diverges from configuration`

### S6 — Adjacent: non-blocking async read + `HttpClient` lifecycle · tier `routine`

Red-first, then green:

| Test | Given | When | Then |
|---|---|---|---|
| `shouldNotCompleteSynchronouslyOnEventLoopForFilesystemLocation` | a temp-file JWKS; running inside `vertx.runOnContext(...)` | `fromJwksAsync(vertx, path)` | `future.isComplete()` is `false` at return — a context cannot dispatch completion while still executing the calling task (RED: `Future.succeededFuture` is already complete) |
| `shouldNotCompleteSynchronouslyOnEventLoopForClasspathLocation` | `classpath:test-jwks.json`; same context | `fromJwksAsync(vertx, location)` | `future.isComplete()` is `false` at return (RED) |

Green: route **all** location kinds through `vertx.executeBlocking` in `fromJwksAsync` (F9); wrap the
JDK `HttpClient` in try-with-resources in `fetchHttp` (F10). Client **reuse/pooling is deliberately
not done** — deferred until measurement shows connection setup affects refresh latency (§11).
Commit: `fix(rest-auth-jwt): keep JWKS reads off the event loop and close the HTTP client`

### S7 — ADR + documentation · tier `routine`

ADR-0206 (§7); rewrite the `module.md` enforcement table, `JwtAuthFactory` capability table
(`module.md:256–262`), `RefreshableJwtAuth` section (`:314–332`) and the two stale "Common mistakes"
bullets (`:535–541`); update the maintainer doc — including **removing** its "Known gap —
`clockSkewSeconds` has no handler-layer backstop" callout (`docs/modules/vertique-rest-auth-jwt.md:96`) and
adding the attestation invariant with its proving test.
Commits: `docs(rest-auth-jwt): update module docs for clock-skew enforcement` (code repo) and a
separate docs-only commit in `vertique-dev`.

### S8 — Stale-documentation cleanup · tier `routine`

File the three new issues (§11) **first** to obtain their numbers, then correct all 8 sites: the
`JwtClaimAuthorizationProvider` javadoc claim (F14) and the 7 wrong `#73` citations (F13).
Commit: `docs(rest-security): correct authorization-provider tracker references and stale claim`

---

## 7. ADRs to write

**ADR-0206 — Apply JWT validation config on every `JWTAuth` construction path** (written in S7).

Records: leeway is permissive-only and therefore cannot have a handler-layer backstop, unlike
`iss`/`aud` (F3–F5); the deliberate 0 → 30 default change and its safety argument (D2); the 300 s
upper bound and its RFC 7519 §4.1.4 basis (D8); internal attestation with fail-fast on divergence and
silence on absence, and why public `ConfiguredJwtAuth` was rejected (D3); defaults-delegation over
mandatory config (D1); the `warnOnMissingConstraints` split (D4); the
`Future<RefreshableJwtAuth>` return-type change (D6); and a **"Deferred alternatives and re-entry
triggers"** section that is the named destination for §11's design deferrals.

Location: `vertique-dev/docs/adr/product/0206-jwt-validation-config-on-every-construction-path.md`; bump
the `Next ADR number:` marker in `vertique-dev/docs/adr/product/README.md:218` to 0207.

> ADR numbers race across parallel worktrees. Re-check `docs/adr/product/README.md` on `main` at merge
> time and expect a renumber, including any textual reference from the module docs.

---

## 8. Artifact manifest

### Code repository — paths relative to `sources/vertique`, branch `fix/jwt-clock-skew-leeway`

**New**
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/ValidationAttested.java`
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/AttestedJwtAuth.java`
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtValidationLeewayTest.java`
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtAuthModuleProvenanceTest.java`
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtValidationConfigBoundsTest.java`
- `docs/plans/fix-jwt-clock-skew-leeway.md` *(removed in the final docs commit)*

**Modified**
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtAuthFactory.java` — S2, S4, S6
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/RefreshableJwtAuth.java` — S4, S5
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtValidationConfig.java` — S5 bounds (D8); javadoc "where enforcement happens"
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtAuthModule.java` — S5 divergence check
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtClaimAuthorizationProvider.java` — S8 javadoc (lines 46–47)
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtBearerSecuritySchemeHandler.java` — javadoc: leeway now always applied
- `vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md` — **canonical, S7**
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/RefreshableJwtAuthTest.java` — S3, S4
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/RefreshableJwtAuthIT.java` — S3, S4
- `vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtAuthFactoryTest.java` — assert delegation, not `null` config
- `vertique-rest/vertique-rest-security/src/main/java/dev/vertique/rest/security/AuthModule.java` — S8 (lines 40, 57)
- `vertique-rest/vertique-rest-security/src/main/java/dev/vertique/rest/security/SecurityPolicyEnforcer.java` — S8 (line 84)
- `vertique-rest/vertique-rest-security/src/main/java/dev/vertique/rest/security/AuthorizationDecisionPoint.java` — S8 (line 32)

**Deleted** — none.

**Module documentation decisions (one per touched BOM-managed consumable)**
- `vertique-rest-auth-jwt`: canonical
  `vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md` **modified**
  (S7) — API additions, a consumer-visible behavior change, and two stale "Common mistakes".
- `vertique-rest-security`: governance maintainer doc `vertique-dev/docs/modules/vertique-rest-security.md`
  **modified** (S8, line 279 tracker reference). Its canonical `module.md` is **not** modified — it
  already documents the not-consulted behavior correctly and cites no issue number, and this change
  alters no application-facing API, behavior, or configuration in that module.

### Governance repository — paths relative to `vertique-dev`, docs-only, direct to `main`

**New**
- `docs/adr/product/0206-jwt-validation-config-on-every-construction-path.md`

**Modified**
- `docs/adr/product/README.md` — next-number marker (line 218) → 0207
- `docs/modules/vertique-rest-auth-jwt.md` — enforcement-layering table (line 87); **remove** the "Known gap"
  callout (lines 96–103); add the attestation invariant and its proving test
- `docs/modules/vertique-rest-security.md` — line 279, `#73` → the new issue number
- `docs/adr/product/0064-typed-security-identity-model.md` — lines 141, 226, same

---

## 9. Risks & edge cases

1. **The check's placement is startup-but-lazy.** `jwtBearerSchemeHandler` is a `@Provides @IntoSet`
   consumed during route registration, so the failure surfaces at boot rather than at
   component-construction. Acceptable: still fail-fast, never first-request. Watch-item, not a
   guarantee.
2. **Attestation is self-reported.** It catches wiring slips, not a determined caller. Stated plainly
   in ADR-0206 rather than sold as enforcement.
3. **`AttestedJwtAuth` must delegate all three `JWTAuth` methods.** Missing either `generateToken`
   overload silently breaks token signing — and the module's own ITs sign tokens, so the gap would
   surface, but only as a confusing IT failure.
4. **`executeBlocking` for classpath reads changes timing** for a caller that relied on
   `fromJwksAsync` completing synchronously. No such caller exists in repo (F8); the `Future`
   contract never promised synchrony.
5. **Leeway now tolerates future-dated `iat`/`nbf` by 30 s** (F4), not just past-dated `exp`. This is
   the same permissive direction, but worth stating in the ADR since the issue framed the setting as
   expiry tolerance only.
6. **Cross-repo, two PRs.** Code + `module.md` in `vertiquehq/vertique` via PR; ADR and maintainer
   docs in `vertique-dev` as a docs-only direct merge. The ADR number must be settled before the
   code-repo docs reference it textually.
7. **IT-touching branch** → the code PR opens as a **draft** and is promoted only on CI green.

---

## 10. Verification

Per slice, from `sources/vertique`:
`./mvnw -ntp -pl vertique-rest/vertique-rest-auth-jwt -am verify` — builds in-reactor deps from
source; never rely on installed SNAPSHOTs across worktrees.

Full gate: `./mvnw -ntp clean verify` on the **whole** project via `build-validator`. The three
example apps' ITs are the real-generated-Dagger-component proof for S5.

New-IT determinism (`testing.md`), from `sources/vertique`:
```
for i in $(seq 50); do ./mvnw -ntp -pl vertique-rest/vertique-rest-auth-jwt verify -Dit.test=RefreshableJwtAuthIT || break; done
```

Mechanical completeness checks — all run from the `vertique-dev` root:
- `grep -rn "#73" --include=*.java --include=*.md sources/vertique docs | grep -v docs/prd/product/archive/` → no hits
- `grep -n "fromJwksAsync(vertx, jwksLocation)" sources/vertique/vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/RefreshableJwtAuth.java` → no hits (both sites carry the config)
- `grep -n "Known gap" docs/modules/vertique-rest-auth-jwt.md` → no hits
- `grep -rn "no overload accepts one" sources/vertique/vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md` → no hits (stale capability-table row replaced)
- `cd sources/vertique && scripts/verify-module-docs.sh` → pass

Acceptance criteria, from the issue:
- *"route `RefreshableJwtAuth.create` through the validating overload"* → S4, proven by S3.
- *"add a test asserting the value reaches `JWTOptions`"* → S1/S2/S3/S4, asserted **behaviorally**
  because Vert.x exposes no accessor (F5).
- *"fail fast when `clockSkewSeconds` is set on a path that cannot honour it"* → S5 (divergence); the
  omission case is closed by construction instead (D1).
- Related finding → documentation corrected in S8; behavior routed out (§11).

---

## 11. Out-of-scope & deferral routing

Every row has a named destination.

| Item | Destination |
|---|---|
| Should a contributed `AuthorizationProvider` have effect, or be rejected at startup? (F14) | **New GitHub issue** `vertiquehq/vertique-dev`, filed in S8; its number replaces all 7 `#73` citations |
| `RefreshableJwtAuth.close()` has no production caller / who owns the timer lifecycle (F12) | **New GitHub issue**, filed in S8 |
| `HttpClient` reuse across fetches; stale-key budget on repeated refresh failure | **New GitHub issue** ("JWKS refresh robustness"), filed in S8. Re-entry: measurement shows connection setup affects refresh latency, or an IdP publishes a key-retirement window |
| Public `ConfiguredJwtAuth` binding contract | **ADR-0206 § "Deferred alternatives and re-entry triggers"** (S7). Re-entry: a real app hand-rolls a raw `JWTAuth`, or a divergence slips past the internal check |
| `JwtAuthModule` owning `JWTAuth` construction from typed config | **ADR-0206 § "Deferred alternatives and re-entry triggers"** (S7). Re-entry: a second app wants config-only setup, or the framework must own JWKS readiness/retry/staleness |
| Builder / parameter object for `JwtAuthFactory` | **ADR-0206 § "Deferred alternatives and re-entry triggers"** (S7). Re-entry: a second independent per-source option (custom HTTP client/TLS, retry, cache budget) |

Issue #37 is closed by this PR; no row above blocks that.

---

## Provenance

- **First opinion & debate:** `vertique-toolkit:vertique-codex-architect`, 2 rounds
  (`codex_session_id: 019faeb3-f50d-70a1-8c4a-724853167e37`). Outcome: it withdrew its public
  `ConfiguredJwtAuth` proposal (D3) and conceded the hidden 0 → 30 loosening needed an explicit
  decision (D2); its preferred mandatory-config API shape was rejected by the user (D1). One of its
  repo-state claims (an existing issue #73 for the authorization-provider question) was **false** and
  is corrected in F13.
- **Plan linter:** `vertique-toolkit:vertique-plan-linter`, 2 rounds. Round 1: 5 blocking FAILs.
  Round 2: 1 blocking FAIL (Class Inventory rows for two new test classes), now added and verified
  by inventory-vs-manifest parity check. Currently **PASS**.

## Amendments

| # | Date | Trigger | What changed |
|---|---|---|---|
| A1 | 2026-07-30 | Plan gap found executing S2 | S3 re-framed from a **red** slice to a **regression-guard** slice. S2's defaults-delegation routes through the 2-arg `fromJwksAsync`, which is exactly what `RefreshableJwtAuth` calls at both fetch sites — so S3's two planned tests are green on arrival. Verified empirically by the S2 implementer with a throwaway probe, not inferred. S3 gains a mandatory revert-and-confirm-red step so the guards are proven able to fail. S4 is unaffected: its explicit-config tests stay genuinely red because the 4-arg `create` does not exist. No contract, scope, or consumer-visible behavior changed — no sign-off required. |
| A2 | 2026-07-30 | Verified fact discovered during the S2 simplify pass | New pre-flight finding **F16** (below) on `JwtValidationConfig` equality, and S5's divergence check constrained to a field-wise comparison. This corrects a latent defect in the S5 design as written; it does not alter the frozen Contract Appendix. |

**F16 — `JwtValidationConfig` and `JwtAuthConfig` both use identity equality.**
`JwtValidationConfig` carries `@Getter @Builder @Jacksonized @Accessors @JsonAutoDetect
@JsonIgnoreProperties` and **no** `@EqualsAndHashCode`; `javap -p` confirms it declares neither
`equals` nor `hashCode`, so it inherits `Object`'s identity semantics. `JwtAuthConfig` is a record
whose generated `equals` delegates per-component into that identity comparison, so it is equally
unusable for value comparison. The instances S5 compares are *guaranteed* distinct on the commonest
path: `JwtAuthFactory.defaultValidation()` builds a fresh default per call and `JwtAuthConfig`'s
`@JsonCreator` independently builds another.

**Consequence for S5, binding:** the divergence check MUST compare
`applied.clockSkewSeconds() != effective.clockSkewSeconds()` — a field-wise comparison — and MUST NOT
use `.equals()` on either config type. An `.equals()`-based check would report divergence and fail
startup on essentially every default deployment. `clockSkewSeconds` is also the only field that needs
checking: `iss`/`aud` have the handler's post-authentication backstop, which is the whole asymmetry
§1 rests on. Do **not** resolve this by adding `@EqualsAndHashCode` — that adds `equals`/`hashCode` to
a public API type outside the frozen §4 appendix and would require sign-off.

## Execution-start drift reconciliation (2026-07-30, before slice 1)

§2 was verified against the submodule gitlink `d914242`. At branch-creation time `origin/main` was
**84 commits ahead** at `4331c5f`, and the governance repository had been restructured in the same
window. Both were reconciled here rather than adapted to silently; the branch is cut from
`origin/main`, not the stale local `main` ref (which sat at `8c881cd`).

**Code repository — drift is javadoc-only; every §2 finding survives.** Two upstream commits touched
the manifest paths (`ccda7e2`, `33207a1`), replacing the `DaggerAppComponent`/`deployAll()` bootstrap
recipes with `VertiqueApplicationBootstrap.start(...)` in `JwtAuthFactory`, `RefreshableJwtAuth`, and
`module.md`. No logic changed. Re-verified against `origin/main`: both un-configured
`fromJwksAsync(vertx, jwksLocation)` calls still present; `close()` still has no production caller
(F12); `RefreshableJwtAuth` still module-internal (F8). Anchors updated — `RefreshableJwtAuth.java`
`:118`→`:121` and `:199`→`:202`; `module.md` `RefreshableJwtAuth` section `:303–310`→`:314–332` and
Common mistakes `:519–527`→`:535–541`. The `JwtAuthFactory` capability table `:256–262`,
`JwtBearerSecuritySchemeHandler.java:210`, `JwtClaimAuthorizationProvider.java:46–47`, and the four
`vertique-rest-security` `#73` sites are unmoved.

**Governance repository — layout changed under the plan.** ADRs moved `adr/` → `docs/adr/`, and
maintainer module docs `docs/<artifactId>.md` → `docs/modules/<artifactId>.md`. Four product ADRs
also landed in the window, so the reserved number moves **0202 → 0206** (marker now
`docs/adr/product/README.md:218`, bumped to 0207). All governance manifest paths, the S7 targets and
the §10 greps were repointed. One new consequence: the restructure surfaced two further `#73`
citations inside an archived PRD, deliberately left uncorrected (see the note under F13).

Nothing in §3's decisions, §4's Contract Appendix, or the slice ordering is affected — this
reconciliation is path and line-anchor only.
