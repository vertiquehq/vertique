# Failed streaming response termination

## Context and goal
A source failure after response handoff currently ends a chunked stream normally, allowing clients to accept a partial body. Reset an open failed response so supported protocols expose incomplete delivery.

## Pre-flight facts
Baseline: source origin/main 283a00cf80e8a53d754edb37a9bf260f147ed76f; governing parent origin/main 81997b0e87a4ac6f332cc9168332af7352dd7d12. Serializer uses endOnFailure(false); pipeline records wire failure before cleanup and has guarded reset with HTTP/1 fallback. The existing integration test demonstrates clean termination. HTTP/1.0 close-delimited bodies cannot reliably signal truncation.

## Slices
1. **Regression and fix (elevated risk: client-visible wire behavior).** Change focused pipeline and streaming integration assertions to require reset after failure; initial red: chunked and equal-length failure still end and integration client ends normally. Run `./mvnw -ntp -pl vertique-rest/vertique-rest-jaxrs -am verify -Dtest=ResponsePipelineTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=StreamingWireFailureIT -Dfailsafe.failIfNoSpecifiedTests=false`. Implement reset of all open failed responses while preserving failure recording, request context, hooks and guarded reset fallback. Re-run green. Commit `fix(rest-jaxrs): reset failed streaming responses`.
2. **Final verification (elevated risk: client-visible wire behavior; verification-only, no additional commit).** Run `./mvnw -ntp clean verify` and diff coverage gate; obtain independent review and resolve findings. Open PR and monitor CI to completion.

## Artifact ownership forecast
Slice 1 modified: `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/ResponsePipeline.java`, `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ResponsePipelineTest.java`, `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/StreamingWireFailureIT.java`, and `vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md`. New: `docs/plans/rest-stream-failure/termination/plan.md`. All product files exist at baseline; plan does not. Add focused test helpers only if necessary. Slice 2 changes no product files unless review identifies an in-scope correction.

## Risks
A reset is observable to clients and HTTP/1 loses reuse; test framing on the wire. HTTP/2 reset must preserve sibling streams; verify where feasible. An already complete fixed-length body cannot be made incomplete retroactively. HTTP/1.0 EOF is ambiguous. Hooks and completion event behavior must remain single-fire.

## Out-of-scope routing
Pre-commit 500 remapping and application-level completion markers and HTTP/1.0 completeness guarantees are recorded in the Deferred decisions section of this plan for separate product decisions. Parent gitlink update follows source merge under the governing repository git workflow and is not part of this source PR.

## Decisions and verification
Use existing reset seam for all still-open failures rather than clean end; do not buffer streams. Criterion: client-visible stream failure and recorded server failure; tests coordinate first chunk before injected failure. Unit tests cover pre-head, ended, equal length, context and reset fallback. HTTP/2 multiplexing and HTTP/1.0 semantics should have integration proof where feasible. Final full verification and independent diff review required. Architecture-review extension is outside Codex harness scope. Evidence gate is off; retain plan under project policy.

## Deferred decisions
Whether to send a 500 before commitment, or add an application-level completion marker for close-delimited responses, remains for a separate design.
