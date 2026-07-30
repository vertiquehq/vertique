# Plan: `vertique-rest-test` fixture (issue #210, PR 1 of 2)

## 1. Context & goal

`RestModule.defaultExceptionMapper()` was widened from package-private to `public`
(`vertique-rest-jaxrs/.../RestModule.java:235`) so an out-of-repo harness — enterprise
`BlobRestTestSupport` — could assemble a real `JaxRsRouterMount` for HTTP-level tests. Issue #210
calls that the wrong altitude: ship a proper shared fixture, migrate consumers, then restore the
method.

The widening is a symptom. The disease is that **assembling a production-faithful JAX-RS mount from
outside `dev.vertique.rest.jaxrs` is impossible**: the `Factory` constructor takes 29 arguments, and
13 of the production collaborators it needs (6 encoders, 4 decoders in rest-jaxrs; 3 context
resolvers in rest-core) are package-private across two modules. Every harness therefore hand-rolls
partial stand-ins. Measured cost: **~600 lines of duplication** — 8 copies of `StringEncoder`, 7
byte-identical copies of `JsonEncoder`, 2 of `JsonDecoder`, 7 copies of the same exception-mapper
rule, 9 copies of a ~15-line factory preamble, 2 byte-identical `deleteRecursively` helpers.

**Goal (this PR):** ship `vertique-rest-test`, a BOM-managed test-support module that lets a consumer
assemble a real mount with real production defaults, and migrate the nine
`vertique-rest-validation` ITs onto it. Restoring `defaultExceptionMapper()` is **PR 2** (§9).

**Why now, not deferred:** the fixture is the precondition for un-widening. Every week it does not
exist, new harnesses copy the stub pattern — two of the nine ITs postdate the widening.

**Boring alternative considered:** keep the method public, document it as bootstrap API. User ruled
against it 2026-07-30, and the evidence agrees — the one method is ~1 line of the ~250-line
enterprise harness. Widening it solved almost nothing.

## 2. Pre-flight findings (verified against code 2026-07-30)

1. **The constructor is 29 arguments, not 26** (`JaxRsRouterMount.java:610-639`). Earlier triage said
   26 — a miscount off the enterprise call site.

2. **A proto-fixture already exists and is load-bearing.**
   `vertique-rest-jaxrs/src/test/.../TestFactories.java` (265 lines, package-private, 12 setters)
   serves 9 test classes inside rest-jaxrs. True in-repo consumer count is ~18 classes, not 9.

3. **Variance across the 9 validation ITs: 19 of 29 args are never customized**, 2 are derivable
   (`responseSerializer`, `validationStrategies`), 8 are the real seam — `middlewares`,
   `requestInterceptors`, `exceptionMapperRegistry`, `sortedDecoders`, `sortedEncoders`,
   `httpConfig`, `jaxRsConfig`, `jsonMapperProfileRegistry`, `fileContentVerifiers`.

4. **`JaxRsConfig.validationStrategy` defaults to `"web-validation"`** (`JaxRsConfig.java:89-90`),
   but `RestModule` alone binds only `NoneValidationStrategy`. A graph built from empty config fails
   to resolve its strategy at router-build. A none-validation consumer **must** set
   `jaxrs.validationStrategy=none` explicitly.

5. **`DefaultResponseSerializer` never sorts** — it iterates the handed list and takes the **first**
   `canEncode` match (`:101-110`); `ParameterExtractor` does the same for decoders (`:905`).
   `OrderedExtension` is lower-first, ties broken by FQCN (`OrderedExtension.java:31,54-56`). **List
   order is the entire contract.** This is why the design delegates sorting to `RestModule`'s real
   providers rather than reimplementing it.

6. **The `@Consumes` route gate, not decoder absence, produces every asserted 415**
   (`JaxRsRouteRegistrar.java:344-346,755,796`). Verified across `ConsumesEnforcementIT` and the
   three 415 assertions in `ProfiledErrorResponseIT`.

7. **Falsification check — decisive.** All 10 harnesses checked for the failure mode that would force
   a *replace* seam: a test needing a production encoder/decoder **absent** rather than out-ranked.
   **No such case exists.** No resource in the nine ITs returns `Buffer`, `byte[]`, or
   `ReadStream<Buffer>`, and none produces `text/event-stream`, so four of six production encoders can
   never match. `UploadTempFileCleanupIT`'s `StreamedUploadEncoder` is priority 900 over a
   test-private record. `ProfiledErrorResponseIT`'s String-returning resource is never invoked (a
   middleware 404s every request before routing; `:404-406,427-437`). **Contributions are sufficient;
   no replacement API ships.**

8. **`RequestValidationStrategySelector.select` is `findFirst()` over a `Set`**
   (`.../jaxrs/validation/RequestValidationStrategySelector.java:36`). With duplicate strategy IDs the
   winner is **nondeterministic**, not "first". This kills any design that offers a second install
   path for strategies alongside the module route.

9. **Test-scope `@Component` declarations are already this repo's idiom for exactly this problem.**
   `MagicBytesVerifierRouteIT` — one of the nine migration targets — declares a nested test component
   whose javadoc reads *"Test graph proving opt-in through the public module without naming its
   package-private verifier."* That is precisely the mechanism this plan adopts. 10+ modules do it,
   including `vertique-application-test`. `dagger-compiler` sits in the root pom's
   `annotationProcessorPaths` (`pom.xml:959-978`), so every module — test compilation included — gets
   Dagger processing with **zero** added configuration.

10. **Structural corrections** — several checklist paths are stale:
    - docs live at `sources/vertique/docs/`, **not** `vertique-dev/docs/`
    - `vertique-coverage-report` is at `vertique-dev/coverage/aggregate/pom.xml`; the path in
      `.claude/rules/new-module-checklist.md:59` is wrong
    - ADRs are at `vertique-dev/adr/product/`; **next free is 0205** (re-verify at S1)
    - `docs/architecture.md` has **no module table or dependency graph**; `README.md` has **no
      modules table** — checklist items 10 and 12 require **no edit**. Do not invent one.
    - `verify-module-docs.sh` requires BOM + root pom + `modules.md` + packaged `module.md` to be
      mutually consistent, so those four land in one commit; it hard-fails on any `ADR-NNNN` token
      inside a packaged `module.md`.

11. `vertx-junit5` and `rest-assured` are forced to `test` scope in the root pom (`:849-858`). The
    fixture is deliberately JUnit-free, so no root-pom scope change is needed. No JPMS repo-wide.

12. **Codec visibility — corrected mid-planning.** I initially recorded that ADR-0018 forbids exposing
    the codec implementations. **That was an over-read and is withdrawn.** ADR-0018:43 rules on
    `ResponseHandler` (the pipeline orchestrator) and explicitly cites `ResponseBodyEncoder` — the
    *interface* — as correctly public. It does not decide `StringBodyEncoder`'s visibility. Making the
    codecs public would need a **new** public-API ADR, not a supersession.
    What *does* survive: `ResponseBodyEncoder.java:17-21` documents the intended override model —
    *"Framework defaults use priority 1000; application encoders at the default priority (0)
    automatically take precedence"* — so the sanctioned way to override a default is to contribute one
    that out-ranks it, never to reference it. Widening is therefore **unnecessary**. And it is
    **insufficient**: it would expose the 10 codecs but leave the 3 resolvers, whose
    `ContextHolderResolver` javadoc explicitly rejects application use.

## 3. Design decision & debate outcome

**Chosen (option j): `vertique-rest-test` ships a public Dagger `@Module`, not a compiled
`@Component`.** Each consuming Maven module declares one package-private test `@Component`.

Dagger retains ownership of everything hard: reaching the package-private implementations, unioning
the multibindings, **sorting the encoder/decoder lists through the real `RestModule` providers**,
wiring the same sorted list into both the factory and `DefaultResponseSerializer`, and building
`RestContextResolution` with all three real resolvers. **Ordering and serializer coherence hold by
construction — the fixture never sorts and never calls the 29-arg constructor.**

Why this satisfies every constraint: no production visibility changes; no generated Dagger code in
the published JAR (only a `@Module`, and `RestModule` already establishes that precedent in a BOM
artifact); no split packages; production fidelity is total because the graph *is* production's.

### Debate outcome (`vertique-toolkit:vertique-codex-architect`, 3 rounds, session `019fb210-1196-7b72-aa92-ecdaa6e1bf33`)

**Round 1** — Codex recommended a hybrid (Dagger for defaults, hand-written builder calling the
29-arg constructor), naming its own falsification condition: *"I would require evidence that at least
one test genuinely needs production defaults absent."*

**Round 2** — I ran that check across all 10 harnesses; it came back negative (§2.7). Codex accepted:
*"No test requires production defaults to be absent. Therefore `replaceEncoders`, `replaceDecoders`,
and a general replacement abstraction have no present requirement and should not ship."* The hybrid's
`replaceX` API was withdrawn.

**Round 3** — user rejected all three then-live options. Codex converged on (j).

**Adopted:**
- (j) itself, and the deletion of every replacement API (falsification, §2.7).
- **Drop `ValidationStrategyContext`.** My round-2 design used a deferred
  `Function<ValidationStrategyContext, RequestValidationStrategy>` to avoid depending on
  `vertique-rest-validation`. Under consumer-owned components that indirection is dead weight: the
  validation consumer's component simply **includes `RestValidationModule` directly** and gets the
  real injected `WebValidationStrategy` + `AnnotationSchemaSource`. Dagger becomes the dependency
  inversion point. Keeping it would have added a public context type, a higher-order factory
  contract, a second install path, and — per §2.8 — nondeterministic duplicate-ID exposure.
- Config flows as the production `@VertxConfig JsonObject`, never a post-hoc `JaxRsConfig` swap
  (preserves `SseBodyEncoder` and default-header middleware coherence).
- The `jaxrs.validationStrategy=none` trap (§2.4).

**Rejected, with reason:**
- Codex's round-1 argument that a hand-written 29-arg call is *desirable* because a constructor change
  breaks it at compile time. That argues against the status quo's 18 call sites, not against a
  generated graph, which has zero hand-written call sites and regenerates.
- Calling generated `RestModule_JsonBodyEncoderFactory` accessors directly — reimplements the graph
  against unsupported generated names, and a new production default could be silently omitted.

**Where I corrected myself:** I told the architect ADR-0018 forbids exposing the codecs. Codex pushed
back and was right (§2.12). (f) is still rejected, but on *unnecessary + insufficient*, not on ADR
conflict. Recorded because the plan's earlier draft asserted the stronger claim.

**Where I corrected Codex:** it treated the rest-jaxrs reactor cycle as something to work around,
offering "keep the local facade temporarily." The better move **dissolves** the cycle — rest-jaxrs's
test classes live in the same module as `RestModule`, so they can declare their own test `@Component`
over `RestModule` directly, needing no dependency on `vertique-rest-test` at all. That makes
`TestFactories` deletable. **Routed to a follow-up issue, not this PR** (§11) — it is ~9 more classes
on top of an already large change, and nothing in #210 depends on it.

**Unresolved → carried as R1:** `RestTestFixtureModule`'s seam set becomes a published compatibility
surface (adding a seam is safe; removing one is breaking). Bounded — it is a test artifact — and far
cheaper than widening production codecs, but it is stated in the module doc rather than discovered
later.

## 4. Contract Appendix (frozen)

Package `dev.vertique.rest.test`. Three public types; everything else package-private.

```java
/**
 * Dagger module giving a test graph the framework's real REST collaborators.
 * Include from a test-scope @Component alongside any strategy module the consumer needs.
 */
@Module(includes = {RestModule.class, ConfigParsingModule.class})
public abstract class RestTestFixtureModule {

    /** No binding exists in RestModule/RestCoreModule; every consumer must supply one. */
    @Provides @Nullable
    static SecurityPolicyValidator securityPolicyValidator() { return null; }

    @Provides @ElementsIntoSet
    static Set<Middleware> fixtureMiddlewares(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<RequestInterceptor> fixtureRequestInterceptors(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<ResponseBodyEncoder> fixtureResponseBodyEncoders(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<RequestBodyDecoder> fixtureRequestBodyDecoders(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<ExceptionMapper<?>> fixtureExceptionMappers(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<JsonMapperProfile> fixtureJsonMapperProfiles(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<FileContentVerifier> fixtureFileContentVerifiers(RestTestContributions c);
    @Provides @ElementsIntoSet
    static Set<RestContextResolver> fixtureContextResolvers(RestTestContributions c);
}

/** Immutable, additive-only test contributions. Bound via @BindsInstance. */
public record RestTestContributions(
        Set<Middleware> middlewares,
        Set<RequestInterceptor> requestInterceptors,
        Set<ResponseBodyEncoder> responseBodyEncoders,
        Set<RequestBodyDecoder> requestBodyDecoders,
        Set<ExceptionMapper<?>> exceptionMappers,
        Set<JsonMapperProfile> jsonMapperProfiles,
        Set<FileContentVerifier> fileContentVerifiers,
        Set<RestContextResolver> contextResolvers) {

    public static Builder builder();
    public static RestTestContributions none();

    public static final class Builder {
        public Builder addMiddleware(Middleware middleware);
        public Builder addRequestInterceptor(RequestInterceptor interceptor);
        public Builder addResponseBodyEncoder(ResponseBodyEncoder encoder);
        public Builder addRequestBodyDecoder(RequestBodyDecoder decoder);
        public Builder addExceptionMapper(ExceptionMapper<?> mapper);
        public Builder addJsonMapperProfile(JsonMapperProfile profile);
        public Builder addFileContentVerifier(FileContentVerifier verifier);
        public Builder addContextResolver(RestContextResolver resolver);
        public RestTestContributions build();
    }
}

/**
 * Everything needed to assemble a faithful mount. Obtainable only from the graph — the
 * constructor and accessors are package-private, so a ROOT-less assembly is unreachable by
 * accident — there is no factory-only overload to reach for. It is not *unenforceable*: Dagger
 * publishes `RestTestMount_Factory.newInstance`, and `router(...)` produces the API-only router
 * deliberately. The constructor rejects an empty middleware set, which is the part that is enforced.
 * A class, not a record: a public record forces a public canonical constructor (JLS 8.10.4),
 * which would let a consumer hand-build a partial mount and defeat the point.
 */
public final class RestTestMount {
    @Inject RestTestMount(JaxRsRouterMount.Factory factory, Set<Middleware> middlewares);
    JaxRsRouterMount.Factory factory();      // package-private
    Set<Middleware> middlewares();           // package-private
}

/** Mount + server helpers. Pure Vert.x — no Dagger, no JUnit. */
public final class RestTestMounts {
    public static Future<Router> router(Vertx vertx, RestTestMount mount, Set<Object> resources);
    public static Future<HttpServer> startServer(Vertx vertx, RestTestMount mount, Set<Object> resources);
    public static HttpServer startServerBlocking(
            Vertx vertx, RestTestMount mount, Set<Object> resources, Duration timeout);
    /** Recursively deletes an uploads directory; replaces two byte-identical IT copies. */
    public static void deleteRecursively(Path directory);
}
```

There is **no factory-only overload**. In an unreleased artifact the easier overload would just be
the attractive wrong path — one way to build a mount, and it is the faithful one.

**Invariants (each pinned by a named test in §6):**
- No `replace*` method exists on any type. Contributions are additive; a test overrides a framework
  default by contributing one that out-ranks it (§2.12's documented model).
- **The fixture never constructs or reorders encoder/decoder lists and defines no independent
  ordering policy. Each middleware tier is installed with the production `OrderedExtension`
  comparator at its production-equivalent installation site** — API-scoped by
  `JaxRsRouterMount:312-315`, ROOT-scoped by `RestTestMounts.startServer` mirroring
  `HttpVerticle:118-122`.
- `responseSerializer` is `RestModule`'s, so it is built from the same sorted list the factory gets.
- `RestTestMounts` never closes a caller-supplied `Vertx`.
- `RestTestFixtureModule`'s seam set is a compatibility surface (R1) — documented in `module.md`.
- The fixture assembles **a single JAX-RS mount with its production API and ROOT middleware
  pipelines** — not every `HttpVerticle` customization or server-option step. That boundary is
  stated in `module.md`, not left implied.

**Consumer shape** — one per Maven module, ~13 lines. `vertique-rest-validation/src/test`:

```java
@Singleton
@Component(modules = {RestTestFixtureModule.class, RestValidationModule.class})
interface ValidationMountComponent {
    RestTestMount testMount();

    @Component.Factory
    interface Factory {
        ValidationMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }
}
```

Including `RestValidationModule` directly is what supplies the real `WebValidationStrategy` and
`AnnotationSchemaSource` — no fixture dependency on `vertique-rest-validation`, no strategy seam.

> **Never name a component accessor `factory()`.** A component declaring a `@Component.Factory` gets
> a generated static `factory()` on its `Dagger…` class, and Dagger rejects a component method that
> collides with it. Hence `testMount()` (and, where a component still exposes the raw factory for a
> test that needs synchronous `createRouter` failures, `mountFactory()`).

## Amendments

| Date | Trigger | Change |
|---|---|---|
| 2026-07-30 | S2 execution — the frozen §4 consumer snippet did not compile | Renamed the consumer component's mount accessor `factory()` → `mountFactory()`; added the collision note above. Correction of a verified fact (Dagger rejects the original), not a design change — the `RestTestFixtureModule` / `RestTestContributions` contract is untouched, so no re-sign-off. |
| 2026-07-30 | S5 execution — `ProfiledBodyParseUnderGateIT` hit the slice's STOP condition | **Scope change, user-approved.** Migrate that file's four valid tests; replace its fifth (`noContentType_profiledBody_strictParseStillRuns`) with a unit test covering the null-`Content-Type` branch of `DefaultBoundRequest.bindProfiledJsonBody` directly. See R8 below for the finding. |

### R8 — the falsification check covered codecs, not middlewares

§2.7 verified that adding the production **encoders/decoders** is a pure add. It did not examine
**middlewares**, and the variance table read `middlewares = Set.of()` in eight of nine ITs as "not
customized". That reading was wrong: `Set.of()` does not mean *no customization*, it means
**suppressing five production middlewares** (`RequestContextLifecycle`, `ContextualLoggingMiddleware`,
`DefaultHeadersMiddleware`, `ContentTypeValidationMiddleware`, `RestRequestCompletionEmitter`).

Every migrated IT therefore gains those five. Eight of nine pass unchanged — the fidelity increase is
the point of the fixture. The ninth did not, and the reason matters:
`ProfiledBodyParseUnderGateIT.noContentType_profiledBody_strictParseStillRuns` asserts **400** for a
POST carrying a body with no `Content-Type` against a resource with no `@Consumes`. Production's
`ContentTypeValidationMiddleware` (API-scoped, priority 20, unconditional `@IntoSet` default) fails
exactly that request with **415** before it reaches the binder. The test passed only because the
hand-rolled harness omitted the middleware — it documented behavior no real deployment has.

The defensive branch it aimed at is real and still reachable from a mount without API-scoped
middleware, so the coverage moves to a unit test rather than being deleted.

**Generalization for the remaining migrations:** treat `Set.of()` for any multibound argument as
*suppression*, not *default*, and check what the production set contains before assuming a pure add.

| 2026-07-30 | Post-review — security review + a fourth architect round found the fixture installs no ROOT-scoped middleware | **Contract Appendix change, user-approved.** Added the opaque `RestTestMount` handle; `router`/`startServer`/`startServerBlocking` now take it instead of a bare factory, and the factory-only forms are dropped. `startServer` installs ROOT-scoped middlewares. Restated the "never sorts" invariant precisely (see R9). |

### R9 — the fixture installed no ROOT-scoped middleware

`JaxRsRouterMount:313` installs only `scope() == API` middlewares; in production `HttpVerticle:118-122`
installs the ROOT-scoped ones on the main router. `RestTestMounts.startServer` built a **bare** root
router, so four of the five middlewares `RestCoreModule` contributes never ran —
`RequestContextLifecycle`, `DefaultHeadersMiddleware`, `ContextualLoggingMiddleware`,
`CorrelationIngressMiddleware` — and `Middleware.scope()` **defaults to ROOT**, so a contributed
middleware was silently discarded.

**Why this was a defect rather than a documented gap.** `RequestLocaleInterceptor:99-108` calls
`RequestContextLifecycle.fromRoutingContext(rc)` and, on throw, closes the scope and **fails the
request** — its own comment calls that branch "a framework-wiring defect (the ROOT
`RequestContextLifecycle` middleware did not run)". `WebSocketEndpointRegistrar:384` does the same.
So the fixture did not merely lose fidelity: it made a documented-unreachable error branch the normal
path, and `vertique-rest-localization` and websocket adopters could not have used it at all.

**On the superseded "never sorts" invariant.** Triage initially rejected the fix as contradicting it.
That over-applied the rule. The invariant exists because `DefaultResponseSerializer` takes the *first*
`canEncode` match and never sorts, so encoder/decoder **list** order is the whole contract and is
delegated to `RestModule.sortedResponseBodyEncoders` / `sortedRequestBodyDecoders`. Middleware
bindings are **sets**, there is no `sortedMiddlewares` provider anywhere to delegate to (verified),
and *both* production installation sites sort inline with `OrderedExtension.comparator()`. Sorting
middlewares at the installation site therefore reproduces production rather than inventing policy.

**Why an opaque handle rather than a fourth parameter.** A `startServer(vertx, factory, resources,
Set<Middleware>)` overload lets a consumer pass `Set.of()` and silently rebuild the defect. The handle
removes that reach-for-it path — that is its justification today, not future extensibility.

**Correction (round-2 review).** This section originally claimed the handle makes the unfaithful
assembly *unrepresentable*. **That was false**, on two counts: Dagger emits `RestTestMount_Factory`
as a public class with a public static `newInstance(Factory, Set<Middleware>)` and ships it in the
JAR, so a determined consumer can reconstruct the ROOT-less pipeline; and `RestTestMounts.router(...)`
returns the API-only router *by design*, documented as such. The honest claim is **unreachable by
accident**, not enforced. The one thing that *is* enforced: `RestTestMount`'s constructor rejects an
empty middleware set (safe because every fixture graph carries at least five ROOT middlewares —
pinned by `RestTestMountTest.fixtureGraphAlwaysCarriesTheBuiltInRootTier`, so if that premise ever
breaks a maintainer sees the premise fail rather than an unexplained rejection at a consumer).
This was the same defect class as round 1's "never hangs": a claim the code did not back.

**Watch-items for the migration:** `UploadTempFileCleanupIT` and `FileVerifierEventLoopNonStallIT`
are the end-handler-ordering-sensitive ITs (`RequestContextLifecycle` registers its end handler first
so it fires last). Run those before the others. Header assertions are low risk — no migrated IT
asserts a complete header set.

**Deferred with re-entry triggers:** `MountCustomizer` and `RouterCustomizer` (a named adopting test
needs one); multi-mount sorting/overlap (the fixture must host more than one `RouterMount`);
production `HttpServerOptions` (a test asserts TLS/compression/timeout); extracting the ROOT-installer
into `rest-core` (install logic exceeds ~5 lines or gains a second caller).

## 5. Class Inventory

| Type | Module | Package | Kind | Visibility |
|---|---|---|---|---|
| `RestTestFixtureModule` | vertique-rest-test | `dev.vertique.rest.test` | `@Module` abstract | **API** |
| `RestTestContributions` | vertique-rest-test | `dev.vertique.rest.test` | record | **API** |
| `RestTestContributions.Builder` | vertique-rest-test | `dev.vertique.rest.test` | static nested class | **API** |
| `RestTestMounts` | vertique-rest-test | `dev.vertique.rest.test` | final class | **API** |
| `RestTestMount` | vertique-rest-test | `dev.vertique.rest.test` | final class (opaque handle) | **API** (type only; ctor + accessors package-private) |
| `package-info` | vertique-rest-test | `dev.vertique.rest.test` | — | API doc |
| `FixtureSelfTestComponent` | vertique-rest-test | `dev.vertique.rest.test` | `@Component` | Test-fixture |
| `ValidationMountComponent` | vertique-rest-validation | `dev.vertique.rest.validation` | `@Component` | Test-fixture |
| `MountFixtures` | vertique-rest-validation | `dev.vertique.rest.validation` | final class | Test-fixture |

## 6. Slice plan

### S1 — module skeleton + registration · `routine`
**Red:** `scripts/verify-module-docs.sh` fails (new artifact in BOM, no index row / packaged doc).
**Green:** create the module; add all four registration points.
**Proof:** script prints the parity line with the artifact count incremented by one.
**Commit:** `build(rest-test): add vertique-rest-test module skeleton and registration`

> BOM + root pom + `docs/modules.md` + packaged `module.md` land in ONE commit (§2.10).

### S2 — fixture module + contributions · `critical`
**Red tests** (`FixtureGraphTest`, using an in-module `FixtureSelfTestComponent`):
- `graphYieldsAllSixProductionEncodersInSortedOrder` — given a component over
  `RestTestFixtureModule` with `RestTestContributions.none()`, when the factory's encoder list is
  read, then it contains exactly `SseBodyEncoder, BufferBodyEncoder, ByteArrayBodyEncoder,
  ReadStreamBodyEncoder, StringBodyEncoder, JsonBodyEncoder` in `OrderedExtension` order (assert on
  class names — the classes are not visible here).
- `graphYieldsAllFourProductionDecodersInSortedOrder` — same, `JsonRequestBodyDecoder` last.
- `contextResolutionResolvesRoutingContext` — resolves `RoutingContext`, unlike a bare
  `new RestContextResolution(Set.of())`. *This is the seam proving we reached rest-core's
  package-private resolvers — the thing no other option achieves without widening.*
- `contributedEncoderSortsByPriorityNotInsertionOrder` — given an encoder of priority 900 contributed
  last, when the list is read, then it is **first**. *Pins §2.5; prove it by reverting to see it red.*
- `noneStrategyConfigResolvesAtRouterBuild` — given `jaxrs.validationStrategy=none`, when a router is
  built, then it succeeds. *Pins the §2.4 trap.*
- `contributionsAreAdditiveNotReplacing` — given a contributed `ResponseBodyEncoder`, when the list is
  read, then all six production encoders are **still present**. *Pins the §2.7 falsification result as
  a standing contract.*

**Green:** `RestTestFixtureModule`, `RestTestContributions` (+ `Builder`).
**Commit:** `feat(rest-test): add Dagger fixture module and additive contributions`

### S3 — mount/server helpers · `critical`
**Red tests** (`RestTestMountsTest` + `RestTestMountsIT`):
- `servesRequestThroughRealMount` (IT) — resource returning `String` → 200 + `text/plain` from the
  **production** `StringBodyEncoder`.
- `mapsExceptionThroughRealDefaultMapper` (IT) — resource throwing `NotFoundException` → 404 +
  `application/problem+json`. *Proves the fixture supplies what the `defaultExceptionMapper()`
  widening bought — the precondition for PR 2.*
- `serializerSelectsContributedEncoderOverProductionDefault` — pins serializer/factory coherence.
- `doesNotCloseCallerSuppliedVertx` — the caller's `Vertx` is usable after `startServer`.
- `startServerBlockingTimesOutCleanly` — a timeout surfaces as an exception, not a hang.

**Green:** `RestTestMounts`.
**Commit:** `feat(rest-test): add mount and server helpers`

### S4 — migrate three proof ITs · `routine`
`UploadTempFileCleanupIT` (uploadsDirectory + requestInterceptor + priority-900 encoder),
`ProfiledErrorResponseIT` (middlewares + profiles + custom mapper rules),
`FileVerifierRejectionIT` (fileContentVerifiers + real injected `WebValidationStrategy`).
**Red:** none new — **the existing assertions are the signal.** They must pass unchanged.
**Proof:** each IT's assertions byte-identical before and after; only assembly changes. Add
`ValidationMountComponent` + `MountFixtures`; delete each IT's local stubs.
**Commit:** `test(rest-validation): migrate three ITs onto the rest-test fixture`

> If a migrated IT needs an assertion *changed* to pass, stop — that is a §2.7 falsification miss and
> a plan gap to amend, not something to absorb.

### S5 — migrate the remaining six ITs · `routine`
`WebValidationGateIT`, `MagicBytesVerifierRouteIT`, `MultipartFilePartValidationIT`,
`ProfiledResponseSerializationIT`, `ProfiledBodyParseUnderGateIT`, `FileVerifierEventLoopNonStallIT`.
Same contract as S4. Also delete `JsonBodyEncoderTestAccess` (the split-package hack the fixture
obsoletes) and both `deleteRecursively` copies.

> `MagicBytesVerifierRouteIT` already declares its own test component (§2.9) — fold it into
> `ValidationMountComponent` rather than leaving two.
> `MultipartFilePartValidationIT` overlaps branch `fix/multipart-part-count-probe` (#220/#223, owned
> by another session). **Check that branch first**; if still in flight, migrate the other five and
> route this one to an issue.

**Commit:** `test(rest-validation): migrate remaining ITs onto the rest-test fixture`

### S6 — ADR + docs · `routine`
ADR **0205** (re-verify the number first): *"Test fixtures reach package-private framework
collaborators through a shipped Dagger module and consumer-owned components, not by widening
production visibility."* Records the rejected alternatives, the falsification evidence, and the
§2.12 correction.
Maintainer doc `vertique-dev/docs/vertique-rest-test.md` carries ADR traceability — **banned** from
the packaged `module.md`. The packaged doc states R1 (the seam set is a compatibility surface).

**Red (executable gate):** seed the packaged `module.md` with an `ADR-0205` token and confirm
`verify-module-docs.sh` **fails** on the private-decision-record rule (`:55`), then remove it. Proves
the gate is live rather than assumed.
**Proof obligations:**
- `verify-module-docs.sh` exits 0 with the new artifact counted.
- Packaged doc contains no `ADR-NNNN` token and none of the forbidden headings.
- Every backticked `docs/…`, `examples/…`, `scripts/…` path in it resolves (`:353-363`).
- It carries the `> **Status:**` blockquote (`:64`) and follows the section order in
  `sources/vertique/docs/module-doc-template.md:84`.
- ADR 0205 still unclaimed at write time (`ls vertique-dev/adr/product/ | tail`).

**Commit:** `docs(rest-test): add ADR-0205 and module documentation`

## 7. Artifact manifest

**New**
- `sources/vertique/vertique-rest/vertique-rest-test/pom.xml`
- `.../vertique-rest-test/src/main/java/dev/vertique/rest/test/RestTestFixtureModule.java`
- `.../src/main/java/dev/vertique/rest/test/RestTestContributions.java`
- `.../src/main/java/dev/vertique/rest/test/RestTestMounts.java`
- `.../src/main/java/dev/vertique/rest/test/package-info.java`
- `.../src/main/resources/META-INF/vertique/module.md`
- `.../src/test/java/dev/vertique/rest/test/{FixtureSelfTestComponent,FixtureGraphTest,RestTestMountsTest,RestTestMountsIT}.java`
- `sources/vertique/vertique-rest/vertique-rest-validation/src/test/java/dev/vertique/rest/validation/{ValidationMountComponent,MountFixtures}.java`
- `vertique-dev/adr/product/0205-test-fixtures-via-shipped-dagger-module.md`
- `vertique-dev/docs/vertique-rest-test.md`

**Modified**
- `sources/vertique/vertique-rest/pom.xml` — `<modules>`
- `sources/vertique/pom.xml` — `<dependencyManagement>` (REST block, after line 333)
- `sources/vertique/vertique-bom/pom.xml` — after line 157
- `vertique-dev/coverage/aggregate/pom.xml` — after line 235
- `sources/vertique/docs/modules.md` — one row after line 76 (`LC_ALL=C` order)
- the nine `vertique-rest-validation` ITs (§S4/S5)

**Deleted**
- `.../vertique-rest-validation/src/test/java/dev/vertique/rest/jaxrs/JsonBodyEncoderTestAccess.java`
- ~24 private stub classes inside the nine ITs (8× `StringEncoder`, 7× `JsonEncoder`, 2× `JsonDecoder`,
  2× `deleteRecursively`, plus per-IT factory preambles)

**Not modified, deliberately:** `docs/architecture.md`, `README.md` (§2.10 — neither has a module
table); `RestModule.java` (PR 2); `TestFactories.java` (follow-up issue, §11).

**Module documentation decisions**
- `vertique-rest-test`: **new** canonical `module.md` (S1, expanded S6) + maintainer
  `docs/vertique-rest-test.md`
- `vertique-rest-jaxrs`: *no documentation impact* — no production source changes in this PR
- `vertique-rest-core`: *no documentation impact* — untouched
- `vertique-rest-validation`: *no documentation impact* — test-tree-only changes

## 8. Risks & edge cases

- **R1 — `RestTestFixtureModule`'s seam set is a published compatibility surface.** Adding an
  `@ElementsIntoSet` seam is safe; removing one is breaking. Bounded (test artifact), stated in the
  packaged `module.md`. Surfaced by the architect; accepted.
- **R2 — the 1100 tie.** Production `JsonBodyEncoder` and the ITs' stub `JsonEncoder` share priority
  1100; the FQCN tie-break puts `dev.vertique.rest.jaxrs.*` first, so production takes over JSON
  encoding in six harnesses. Verified safe today (no test asserts on those bytes), but a real
  behavioral delta — S4/S5 confirm assertions pass unchanged.
- **R3 — list order is the whole contract** (§2.5). Delegated to `RestModule`; pinned by
  `contributedEncoderSortsByPriorityNotInsertionOrder`.
- **R4 — duplicate strategy IDs are nondeterministic** (§2.8). Mitigated by having exactly one
  install path: include the strategy's own module.
- **R5 — residual Dagger version skew.** (j) reduces but does not eliminate it: `RestModule_*Factory`
  classes already ship precompiled in framework JARs, an exposure every application component already
  has. The BOM pins `dagger` and `dagger-compiler` together (`vertique-bom/pom.xml:535-545`), so
  BOM-importing consumers align for free.
- **R6 — `MultipartFilePartValidationIT` collides with an in-flight branch** (§S5).
- **R7 — ADR number race** — 0205 verified 2026-07-30; parallel sessions race the marker.

## 9. Cross-repo sequencing (PR 2 out of scope)

1. **PR 1 — this plan.** Fixture ships; visibility unchanged; nothing breaks.
2. **Enterprise** migrates `BlobRestTestSupport` onto the released fixture — it parents to
   `vertique-parent`, already declares the `dagger` runtime dep (`vertique-blob-rest/pom.xml:52`), and
   already has 8+ test-scope `@Component`s, so the cost is one component declaration and **zero** build
   configuration. Note the enterprise framework pin `0.0.0-repository001.…-SNAPSHOT` is unresolvable;
   compatibility runs need the documented override.
3. **PR 2 — framework.** Restore `defaultExceptionMapper()` to package-private.

Do not collapse 1 and 3: `public` → package-private is source *and* binary incompatible.

## 10. Verification

Per slice: `./mvnw -ntp -pl vertique-rest/vertique-rest-test -am verify`
(always `-am`; comma-separate any `-Dtest` — `+` matches zero tests and still reports SUCCESS).

Gate: `scripts/verify-module-docs.sh` after S1 and again after S6.

Full: `./mvnw -ntp clean verify` from `sources/vertique`, serialized against other worktrees.

**Mechanical completeness check:**
```bash
grep -rn "class StringEncoder\|class JsonEncoder\|class JsonDecoder" \
  vertique-rest/vertique-rest-validation/src/test/ | wc -l   # expect 0
test ! -e vertique-rest/vertique-rest-validation/src/test/java/dev/vertique/rest/jaxrs/JsonBodyEncoderTestAccess.java
```

**Acceptance walkthrough:**
- #210's premise — fixture replaces what the widening bought → `mapsExceptionThroughRealDefaultMapper`
- production fidelity → `graphYieldsAllSixProductionEncodersInSortedOrder`,
  `contextResolutionResolvesRoutingContext`
- no widening → `git diff main...HEAD -- '*/src/main/*'` touches no file under `vertique-rest-jaxrs`
  or `vertique-rest-core`
- consumers migrate → nine ITs green with unchanged assertions
- PR 2 unblocked → no caller of `defaultExceptionMapper()` outside `dev.vertique.rest.jaxrs`

## 11. Out-of-scope & deferral routing

| Item | Route |
|---|---|
| Restore `defaultExceptionMapper()` to package-private | PR 2 (§9); tracked by #210 |
| Migrate rest-jaxrs's 9 `TestFactories` consumers onto their own test `@Component` over `RestModule`, delete `TestFactories` | **GH issue.** Now possible (the cycle dissolves — §3), but ~9 more classes on an already large PR and nothing in #210 depends on it |
| `MultipartFilePartValidationIT` if the multipart branch is still in flight | GH issue, referencing #220/#223 |
| `vertique-coverage-report` path stale in `new-module-checklist.md:59`; items 10/12 mandate edits to files with no such sections | GH issue (docs fix) |
| `ValidationStrategyContext` / higher-order strategy seam | Deferred — re-enter only if a consumer must build a strategy from graph-owned deps and cannot express it as a local `@Provides @IntoSet` or an included module |
| A `vertique-rest-validation-test` artifact | Deferred — re-enter at ≥2 external consumers |
| Making the 10 codecs public API on their own merits | Deferred — needs a **new** public-API ADR (§2.12), not this PR |
