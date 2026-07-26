# Minimal Vertique Maven Archetype

## Context and goal

Vertique has comprehensive examples but no releaseable minimal application starter. This change
adds a Maven archetype so a consumer can create a BOM-pinned application without copying optional
security, OpenAPI, or logging features from a richer example. The do-less alternative—documenting
copying an example—is rejected because it cannot provide a four-choice starter contract.

## Baseline and pre-flight findings

Baseline: `main` at `133d754` (verified 2026-07-26).

- The SSE example establishes the lifecycle component modules and deploys management in `INFRA`
  before HTTP in `EDGE`.
- `ManagementVerticle` publishes its bound random port in local shared data as
  `vertique` / `management.port`; empty health-check sets are UP.
- `VertiqueAppExtension` boots a generated `@VertiqueApp` factory and exposes the HTTP port.
- The BOM manages Dagger and Vertique code generators but not `dagger-compiler`.
- Standard Maven Archetype batch generation also requires a generated-project version. The
  supported wrapper supplies it as `0.1.0-SNAPSHOT`, leaving four user choices.

## Frozen contract

`dev.vertique:vertique-archetype` is a non-BOM `maven-archetype` module. The generated project:

- takes exactly `groupId`, `artifactId`, `package`, and `vertiqueVersion` through
  `bin/new-vertique-app`; the wrapper supplies `version=0.1.0-SNAPSHOT`;
- imports `dev.vertique:vertique-bom:${vertiqueVersion}` and uses versionless framework and
  Dagger dependencies;
- pins compiler `3.15.0`, Failsafe `3.5.5`, exec `3.0.0`, and Jib `3.5.1`;
- configures Jib for `eclipse-temurin:21-jre`, target image `${project.artifactId}`, and
  `dev.vertique.launcher.VertiqueApplication`;
- contains a package-private `@VertiqueApp` Dagger component extending
  `VertiqueApplicationComponent`, an `AppModule` deploying management in `INFRA` and HTTP in
  `EDGE`, and a `GET /hello` JAX-RS resource;
- enables management health endpoints and provides one integration-test class that verifies both
  `/hello` and `/health/live` on ephemeral ports.

The BOM will also manage `dagger-compiler` at the framework's Dagger version plus the generated
test dependencies `junit-jupiter` and `rest-assured`, so the standalone generated POM remains
fully version-pinned by the selected BOM.

## Slice plan

### Slice 1 — scaffold implementation (routine)

**Red proof:** `ArchetypeGenerationIT#generatesMinimalApp` — given four wrapper values, a fixture
directory, and a fake Maven executable, when the wrapper runs with closed standard input, then it
passes the exact non-interactive archetype command, selected BOM version, and fixed generated
project version without requesting another application value. The archetype is not yet released at
this source-tree test point, so template rendering is proven by the Slice 2 integration fixture.

**Green implementation:** Register the module and BOM compiler management; add archetype metadata,
POM, templates, generated component/module/resource/configuration, generated-project POM, and the
four-flag launcher. The generation proof depends on the launcher, so it is part of this slice rather
than Slice 2.

**Commit:** `feat(archetype): add minimal application scaffold`

### Slice 2 — executable generated-project proof and usage docs (routine)

**Red proof:** `GeneratedApplicationIT#servesHelloAndLiveness` — given a generated app with both
ports set to zero, when `mvn -ntp verify` runs, then its application test returns 200 for
`/hello` and `/health/live`.

**Red proof:** `ArchetypeReadmeIT#documentsSupportedCommands` — given the archetype README and
the generated application's README, when their text is parsed, then the former contains the
four-flag launcher and the latter contains `mvn -ntp exec:java`, `mvn -ntp verify`,
`mvn -ntp package`, and `mvn -ntp jib:dockerBuild`. The generated project must not advertise a
launcher it does not contain.

**Green implementation:** Add the generated README and Maven Archetype integration fixture that
runs the generated project verification.

**Commit:** `test(archetype): verify generated minimal application`

## Artifact manifest

### Modified

- `pom.xml`
- `vertique-bom/pom.xml`

### New

- `vertique-archetype/pom.xml`
- `vertique-archetype/README.md`
- `vertique-archetype/bin/new-vertique-app`
- `vertique-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`
- `vertique-archetype/src/main/resources/archetype-resources/pom.xml`
- `vertique-archetype/src/main/resources/archetype-resources/README.md`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/java/AppComponent.java`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/java/AppModule.java`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/java/package-info.java`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/java/resource/HelloResource.java`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/java/resource/package-info.java`
- `vertique-archetype/src/main/resources/archetype-resources/src/main/resources/config/application.json`
- `vertique-archetype/src/main/resources/archetype-resources/src/test/java/ApplicationIT.java`
- `vertique-archetype/src/test/java/dev/vertique/archetype/ArchetypeGenerationIT.java`
- `vertique-archetype/src/test/java/dev/vertique/archetype/ArchetypeReadmeIT.java`
- `vertique-archetype/src/test/resources/projects/minimal/archetype.properties`
- `vertique-archetype/src/test/resources/projects/minimal/goal.txt`
- `vertique-archetype/src/test/resources/projects/minimal/verify.groovy`

`vertique-archetype` is not BOM-managed, so no canonical module document or module-index change is
required. `vertique-bom` has no canonical module document.

## Risks and verification

- Resolve an external released Vertique fixture in isolation to catch publication or processor
  gaps.
- Validate wrapper package/version values before invoking Maven.
- Compile the generated fixture in the Slice 2 integration fixture to catch archetype token
  escaping errors.
- Use port `0` and the management shared-data entry to prevent test port collisions.

Run focused archetype verification, then `./mvnw -ntp clean verify` from the source root. The
acceptance walkthrough checks the four arguments, BOM import and versionless dependencies, generated
factory startup, hello and health responses, and all documented run/package commands.

## Out of scope

Create follow-up issues after the PR for alternate REST/auth/database archetypes, Maven Central
archetype-catalog registration, and a Windows launcher.

## Amendments

- 2026-07-26 — Plan gap: moved the launcher from Slice 2 to Slice 1 because the approved Slice 1
  red proof invokes it. This preserves the frozen public interface and all acceptance criteria.
- 2026-07-26 — Test isolation: changed Slice 1's launcher proof to capture its Maven invocation
  through a fake executable; source-tree tests cannot resolve an unreleased archetype remotely.
  Slice 2 retains the real rendering and generated-application verification obligation.
- 2026-07-26 — Documentation correction: moved the generator launcher command from the generated
  README to the archetype README, because the generated application does not contain that launcher.
  The generated README retains its run, verification, package, and Jib commands.
- 2026-07-26 — Native archetype verification: replaced an inactive generic Maven Invoker fixture
  with Maven Archetype's lifecycle-owned `src/it/projects` fixture (`archetype.properties`,
  `goal.txt`, `verify.groovy`). This makes `verify` generate from the just-built archetype and run
  the generated application's integration test without staging a separate local repository.
- 2026-07-26 — Archetype discovery correction: Maven Archetype 3.4.1 discovers IT projects under
  `${project.build.testOutputDirectory}/projects`; moved the native fixture to
  `src/test/resources/projects` so Maven resources copies it to that required location. This
  replaces the prior `src/it/projects` path and prevents an empty integration-test phase.
- 2026-07-26 — Archetype metadata correction: renamed the descriptor from legacy `archetype.xml`
  to `archetype-metadata.xml`, the current Maven Archetype descriptor name. The old name caused
  the plugin to parse modern `fileSets` metadata as a 1.x descriptor and fail before generation.
- 2026-07-26 — BOM test-dependency correction: native generated-project verification found that
  JUnit Jupiter and Rest Assured were internally parent-managed but absent from the published BOM.
  Added them to the BOM management contract; the archetype's standalone test dependencies remain
  versionless and therefore correctly follow `vertiqueVersion`.
