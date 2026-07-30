# Plan — #52: one supported lifecycle orchestrator

Batch task 4. Code repo `sources/vertique` + meta repo `vertique-dev`.
Risk: low blast radius, but it changes a documented contract and adds a real end-to-end test.

**Provenance.** Approved 2026-07-29. Branch `fix/single-lifecycle-orchestrator`, worktree
`.claude/worktrees/issue-52-lifecycle-orchestrator`, branched from `main` at `9a940b6`. ADR marker
re-checked at S0: `0198`/`0199` are the latest, so **0200** is free (F7 stands).

## 1. Context & goal

Legacy #52 says "VerticleDeploymentManager contributions to a never-deployed phase vanish silently"
and prescribes an explicit finalization gate (`deployAll()`/`finish()`). **Two of its premises are
false**, verified against the code:

- `deployAll()` already exists and already loops `LifecyclePhase.values()`
  (`vertique-deploy/.../VerticleDeploymentManager.java:65-81`). It has zero production callers.
- On the framework path the loss is **impossible**: `VertiqueApplicationBootstrap.java:94-96` loops
  every phase, `:200-208` deploys all four verticle phases, pinned by
  `VertiqueApplicationBootstrapTest.java:123-130`.

The live loss path is **documentation**. `vertique-deploy`'s own canonical doc (~L91-102) teaches a
recipe that deploys `INFRA` and `EDGE` and skips `SERVICES`. A hand-rolled host following it silently
loses every `SERVICES` contribution — the cron scheduler (`CronBaseModule.java:49`), the delayed-job
pollers (`DelayedJobModule.java:311`), and the outbox relay
(`TransactionalMessagingPostgresqlModule.java:238`). No cron job runs, no delayed job executes, no
outbox message is relayed, and startup completes green.

**Goal:** make one orchestrator the only documented path, delete the recipes that teach the broken one,
and prove with a real test that a multibound contribution in every phase actually deploys.

**Why not the gate.** A `finish()` gate is opt-in enforcement on exactly the population whose mistake is
omitting calls. To be correct it must gate on each phase future *settling*, which means owning the phase
chain — reconstructing the runner that already exists. It would also add mutable state to a `@Singleton`
with no coherent reset rule (`EnumSet` is not thread-safe, rollback corrupts it, `undeployAll()` swallows
failures). Rejected, and recorded as a rejected alternative in the ADR so it is not rebuilt.

## 2. Pre-flight findings

- **F1.** Zero framework `BOOTSTRAP` contributions exist, so the recipe's `BOOTSTRAP` omission is
  harmless today. The `SERVICES` omission is the entire live defect.
- **F2.** Delayed-job uses `@ElementsIntoSet`, so it is one *site* producing one deployment per
  configured queue — "three contributions" is three sites, not three deployments.
- **F3.** The load-bearing text is a **contract sentence**, not a code sample:
  `vertique-application/src/main/resources/META-INF/vertique/module.md:34-36` — *"The module is not
  required for applications that still use a hand-written `MainVerticle` — the step and verticle
  deployment APIs exist independently."* That is what legitimizes the broken path.
- **F4.** Existing coverage leaves a real proof gap. `VertiqueApplicationBootstrapTest.java:123-130`
  verifies a **Mockito mock**; `VertiqueBootstrapVerticleIT.java:112-122` hand-builds a manager with a
  single `EDGE` deployment. **Nothing proves a multibound `SERVICES` contribution reaches deployment
  through the real runner** — precisely the loss #52 describes.
- **F5.** `deployPhase` cannot be made non-public: the runner must interleave each phase's
  `ApplicationStartupStep`s *before* that phase's deploy (`VertiqueApplicationBootstrap.java:175-208`),
  so per-phase granularity is a hard requirement of a cross-module caller.
- **F6.** Adding a manager constructor parameter breaks two multibinding declarations —
  `VertiqueAppProcessorTest.java:44-56` (inline source string) and
  `vertique-application-test/src/test/java/.../StubLifecycleModule.java:31-41`. Moot here: no
  constructor change.
- **F7.** ADR numbering: next free is **0200** (`adr/product/README.md:212`). Re-check at S0 — parallel
  sessions race the marker.

## 3. First opinion & debate outcome

`vertique-toolkit:vertique-codex-architect` (`codex_session_id: 019fae63-5b4d-7180-8d8e-89dcf0fff217`).

**Adopted:** the whole reframe — contract correction over code gate; the mark-timing argument that kills
`finish()`; the tiering of recipe sites by severity; the observation that the contract sentence (F3) is
the real decision; and that the sentinel test (F4) is the one piece of code worth adding.

**My own finding argued against my proposal:** I had offered `LifecyclePhase.AFTER_START` (vacant) as a
home for a framework-side gate. That hook is only materialized by the runner, so a gate hosted there
would defend exclusively the path that provably cannot lose contributions.

**Rejected from the consultation:** its position that no test is worth adding — F4 is a genuine gap, and
its own falsification criterion (sentinels through the real runner) is a deliverable, not a hypothesis.
Also rejected: deleting `deployAll()`. It is exhaustive by construction; deleting it pushes hand-rollers
toward the *unsafe* `deployPhase`.

**Unresolved:** none.

## 4. Slice plan

Ordered. `./mvnw -ntp spotless:apply` before any commit touching Java.

### S0 — worktree + persist  *(plan-artifact commit)*
`git worktree add .claude/worktrees/issue-52-lifecycle-orchestrator -b fix/single-lifecycle-orchestrator main`
inside `sources/vertique`. Persist this plan to `docs/plans/fix-single-lifecycle-orchestrator.md`.
Re-check the ADR marker (F7).
Commit: `docs: add implementation plan for single lifecycle orchestrator`

### S1 — the sentinel test  *(critical; the only code)*
**Red test spec** — new `VerticleDeploymentPhaseCoverageIT` in `vertique-application`:
- `everyVerticlePhaseContributionDeploys` — GIVEN a real Dagger component contributing a sentinel
  `VerticleDeployment` via `@Provides @IntoSet` in **each** of `BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`,
  WHEN the application starts through the real `VertiqueApplicationBootstrap.start(runtime, factory)`,
  THEN every sentinel verticle recorded a `start()` call. No mock manager, no hand-built manager.
- `nonVerticlePhaseContributionIsRejectedAtConstruction` — GIVEN a `VerticleDeployment` for a
  non-verticle phase, THEN construction throws (pins the existing compact-constructor guard as the
  reason the runner's loop is safe to write without a filter).
Expected today: the first test should **pass** — the runner is exhaustive. Its value is that it pins the
invariant against a future change and closes F4's proof gap. If it *fails*, the structural work becomes
justified and this plan is wrong — report that rather than adapting.
Commit: `test(application): prove every verticle-phase contribution deploys through the real runner`

### S2 — Tier-1 doc fix  *(routine)*
`vertique-deploy/src/main/resources/META-INF/vertique/module.md` (~L91-102): delete the partial
`deployPhase` chain. Replace with delegation to `VertiqueApplicationBootstrap.start(runtime, factory)`,
and relabel `deployPhase`/`deployAll` as framework choreography primitives rather than application entry
points. State the consequence of a partial chain plainly — an undeployed phase's contributions are
dropped with no error — and name `SERVICES` as the phase carrying cron, delayed jobs, and outbox relay.
Commit: `docs(deploy): replace the partial deployPhase recipe with runner delegation`

### S3 — the contract sentence  *(routine)*
`vertique-application/src/main/resources/META-INF/vertique/module.md` (~L34-36, F3): rewrite to state
there is one supported orchestrator — a custom verticle may replace the framework **host verticle** but
must delegate application startup and shutdown to the runner.
`docs/packaging.md` (~L279-315): correct any wording implying the `vertique.bootstrap.verticle=false`
opt-out replaces the **lifecycle**; it replaces the host verticle only.
Commit: `docs(application): state one supported lifecycle orchestrator`

### S4 — Tier-2 legacy-recipe sweep  *(routine)*
These show retired manual-`MainVerticle` recipes. They use `deployAll()`, so they are phase-exhaustive
and lose nothing — but they teach the path S3 just de-legitimized, so they go in the same change:
- `vertique-core/.../core/json/JacksonConfigurer.java:25`
- `vertique-rest/vertique-rest-auth-jwt/.../JwtAuthFactory.java:89` and `:208`
- `vertique-rest/vertique-rest-auth-jwt/.../RefreshableJwtAuth.java:102`
- `vertique-db/vertique-db-flyway/src/main/resources/META-INF/vertique/module.md:77-90` and `:165`
Replace each with the `@VertiqueApp` + startup-step equivalent, or drop the sample where the surrounding
javadoc does not need one. Javadoc-only in the Java files — no behavior change.
Commit: `docs: retire manual MainVerticle recipes from javadoc and the flyway module doc`

### S5 — ADR + maintainer doc  *(routine)*
ADR **0200** — *One supported lifecycle orchestrator*: the decision, that `deployPhase`/`deployAll` are
framework primitives, why per-phase granularity must stay public (F5), and the rejected `finish()` gate
with the mark-timing and mutable-state reasoning so it is not rebuilt. Bump the marker to 0201.
Meta `docs/vertique-deploy.md`: record that partial `deployPhase` choreography drops contributions
silently and that this is a contract decision rather than a code guard.
Commits: `docs: add ADR 0200 …` / `docs(deploy): record the orchestrator contract in the maintainer reference`

## 5. Artifact manifest

**New — code repo**
- `vertique-application/src/test/java/dev/vertique/application/VerticleDeploymentPhaseCoverageTest.java`
  *(named `Test`, not `IT` — see Amendment 1)*
- `docs/plans/fix-single-lifecycle-orchestrator.md` *(removed in the final docs commit)*

**New — meta repo**
- `adr/product/0200-single-lifecycle-orchestrator.md`

**Modified — code repo**
- `vertique-deploy/src/main/resources/META-INF/vertique/module.md`
- `vertique-application/src/main/resources/META-INF/vertique/module.md`
- `vertique-db/vertique-db-flyway/src/main/resources/META-INF/vertique/module.md`
- `vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md` *(Amendment 2)*
- `docs/packaging.md`
- `vertique-core/src/main/java/dev/vertique/core/json/JacksonConfigurer.java` *(javadoc only)*
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/JwtAuthFactory.java` *(javadoc only)*
- `vertique-rest/vertique-rest-auth-jwt/src/main/java/dev/vertique/rest/auth/jwt/RefreshableJwtAuth.java` *(javadoc only)*

**Modified — meta repo**
- `docs/vertique-deploy.md`
- `adr/product/README.md` *(marker)*

**Module-doc decisions:** `vertique-deploy`, `vertique-application`, `vertique-db-flyway` — canonical
`module.md` listed above, plus `vertique-rest-auth-jwt` (Amendment 2). `vertique-core` — *no
documentation impact*: javadoc sample changes only, no application-facing API, configuration, or
behavior change.

## 6. Risks & edge cases

- **The contract change is the real risk.** It de-legitimizes hand-rolled lifecycle for out-of-repo
  consumers. No in-repo app uses it, so nothing here breaks; the exposure is external and pre-0.1.0.
- **`deployAll()` keeps zero production callers.** Deliberately retained as the safe primitive for a
  manual host — deleting it would push those users to the unsafe `deployPhase`. Named in the ADR.
- **The sentinel test is an IT** touching real startup. Prove determinism with repeated local runs, and
  per `testing.md` open the PR as a draft until CI is green on it.
- **`BOOTSTRAP` has no framework contribution**, so the sentinel test is the only thing exercising that
  phase end to end. That is the point.
- No `ADR-NNNN` reference may appear in a packaged `module.md` — `scripts/verify-module-docs.sh`
  hard-fails on it.

## 7. Verification

- Per slice: `./mvnw -ntp -pl <module> -am verify` (always `-am`; `-Dtest` comma-separated).
- Full gate: `./mvnw -ntp clean verify` on the whole reactor, plus `./mvnw -ntp spotless:check` and
  `scripts/verify-module-docs.sh`. Count tests from the Surefire/Failsafe XML, never by summing stdout
  `Tests run:` lines.
- Mechanical checks:
  - `grep -rn 'deployPhase(LifecyclePhase.INFRA)' --include='*.md' .` → no hit.
  - `grep -rn 'verticleDeploymentManager().deployAll()' --include='*.java' --include='*.md' .` → no hit
    outside the ADR and the maintainer doc.
- Acceptance: a multibound contribution in each of the four verticle phases provably deploys through the
  real runner; no document teaches a partial `deployPhase` chain; the rejected gate is recorded.
- Reviews: `/security-review` is not warranted (docs + one test, no security surface) — `codex-reviewer`
  plus the Codex loop is the review path. State that choice at the review gate rather than skipping
  silently.

## 8. Out of scope & deferral routing

| Item | Why | Route |
|---|---|---|
| Removing `deployPhase` from the public surface | Impossible without relocating lifecycle ownership (F5); pre-0.1.0 API freeze is the natural trigger | Issue, with the 0.1.0 freeze as re-entry trigger |
| Deleting `deployAll()` | Retained on purpose (§6); revisit only when no recipe references it | Issue |
| Amending or closing legacy #52 itself | Its prescribed design rests on two false premises | Close with a pointer when this merges |

## Amendments

Both are as-built corrections of verified facts — neither changes a contract or the scope the user
approved, so no sign-off was required (`planning.md` § Mid-flight amendments).

1. **2026-07-29 — the sentinel test is `…Test`, not `…IT` (correction of a verified fact).**
   `vertique-application/pom.xml` declares no `<build>` section, and the parent lists
   `maven-failsafe-plugin` only in `<pluginManagement>`, so nothing binds Failsafe in this module —
   confirmed empirically by the absence of `target/failsafe-reports` after a full `verify`. A class named
   `…IT` here would compile and never run. `vertique-launcher` gets its ITs executed because it declares
   the plugin itself. The test needs nothing an IT provides (no port, no container; the sentinel `start()`
   completes synchronously), so it is a Surefire test and the reason is recorded in its class javadoc.
   **Adjacent finding, routed:** any future IT added to `vertique-application` would silently not run.

2. **2026-07-29 — a fifth legacy-recipe site, and a module-doc decision flipped (as-built).**
   The S4 sweep found `vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md`
   carrying the same hand-rolled `Verticle.start(Promise)` + `deployAll()` recipe, teaching the same
   `fromJwksAsync` composition point as the two javadoc sites. Fixed identically. The plan had recorded
   `vertique-rest-auth-jwt` as *no documentation impact* on the assumption its changes were javadoc-only;
   that is now a canonical `module.md` change and the manifest says so.

3. **2026-07-29 — `JacksonConfigurer`'s sample was answering a dead question (as-built).**
   S4 expected to modernize its startup sample. Reading it in full revealed `JacksonConfigureStep`, already
   auto-contributed by `CoreLifecycleStepsModule` at the `CONFIGURE` phase, so an application on the runner
   never calls `configure()` directly. The scaffolding was deleted rather than rewritten, and the javadoc
   now states that manual invocation is the non-runner exception case.
