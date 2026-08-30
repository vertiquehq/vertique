// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.bootstrap.BootstrapContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.ExitCodes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end tests for placeholder resolution through a real {@link VertiqueApplication} launch.
 *
 * <p>Exercises the full bootstrap chain: {@link LauncherStubSourceFactory} serves sentinel values
 * from its static map; a real launch resolves placeholders in the {@code --conf} tree; the
 * verticle's deployment config and the {@link BootstrapContext} both reflect the resolved values.
 *
 * <p>Also verifies the AC-8 property-source lifecycle contract:
 * <ul>
 *   <li>Sources are closed exactly once after launch, even when {@link #runShutdownSequence()}
 *       is called multiple times (idempotent).</li>
 *   <li>Two stub sources declared in order are closed in reverse declaration order.</li>
 *   <li>Direct {@code vertx.close()} alone does not invoke the hook pipeline; callers must also
 *       call {@link VertiqueApplication#runShutdownSequence()} explicitly.</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlaceholderEndToEndTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoOpVerticle.class.getName();
    private static final String SENTINEL = "s3cret-SENTINEL-9472";

    // --- Resolved values ---

    @Nested
    @DisplayName("resolved values — verticle config and BootstrapContext see placeholder-resolved tree")
    class ResolvedValues {

        @Test
        @DisplayName("placeholder resolved: db.password == sentinel from stub source; db.url default applied")
        void placeholderResolutionThroughRealLaunch() {
            // Arrange: stub serves the sentinel under "db.password"
            LauncherStubSourceFactory.State.values.put("db.password", SENTINEL);

            // --conf declares two references:
            //  - db.password: resolved from stub source
            //  - db.url: self-referencing default (${db.host:localhost} default applied)
            String conf = "{\"config\":{\"propertySources\":[{\"name\":\"stub\",\"type\":\"launcher-stub\"}]},"
                    + "\"db\":{\"password\":\"${db.password}\",\"url\":\"jdbc:pg://${db.host:localhost}/app\"}}";

            int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf}).launch();

            assertEquals(0, exitCode, "launch must succeed with resolved placeholders");

            // Verticle deployment config assertions
            JsonObject started = NoOpVerticle.startedConfig.get();
            assertNotNull(started, "NoOpVerticle must have started");
            JsonObject db = started.getJsonObject("db");
            assertNotNull(db, "db section must exist in verticle config");
            assertEquals(SENTINEL, db.getString("password"), "db.password must be resolved from stub source");
            assertEquals(
                    "jdbc:pg://localhost/app",
                    db.getString("url"),
                    "db.url must use default 'localhost' when db.host is absent");

            // BootstrapContext (alpha contributor) also saw the resolved values
            JsonObject recorded = TestContributorState.recordedConfig.get();
            assertNotNull(recorded, "alpha contributor must have recorded context.config()");
            JsonObject recordedDb = recorded.getJsonObject("db");
            assertNotNull(recordedDb, "db section must exist in recorded config");
            assertEquals(SENTINEL, recordedDb.getString("password"), "context.config() db.password must be resolved");
            assertEquals(
                    "jdbc:pg://localhost/app",
                    recordedDb.getString("url"),
                    "context.config() db.url must have default applied");
        }
    }

    // --- AC-8: source closed exactly once; reverse order; idempotent ---

    @Nested
    @DisplayName("AC-8 — property sources closed exactly once in reverse declaration order")
    class SourceLifecycle {

        @Test
        @DisplayName(
                "single stub source: close() called exactly once after launch + runShutdownSequence; idempotent on second call")
        void singleSourceClosedExactlyOnce() throws Exception {
            LauncherStubSourceFactory.State.values.put("db.password", SENTINEL);

            String conf = "{\"config\":{\"propertySources\":[{\"name\":\"stub\",\"type\":\"launcher-stub\"}]},"
                    + "\"db\":{\"password\":\"${db.password}\",\"url\":\"jdbc:pg://${db.host:localhost}/app\"}}";

            TestVertiqueApplication app = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf});
            int exitCode = app.launch();
            assertEquals(0, exitCode, "launch must succeed");

            // programmatic vertx.close() alone does NOT fire the launcher hook pipeline
            Vertx vertx = TestVertiqueApplication.capturedVertx.getAndSet(null);
            assertNotNull(vertx, "Vertx must have been captured");
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // runShutdownSequence() must close the source exactly once
            app.runShutdownSequence();
            assertEquals(1, LauncherStubSourceFactory.State.closeCount.get(), "close() must be called exactly once");

            // Second call is idempotent — still 1
            app.runShutdownSequence();
            assertEquals(
                    1,
                    LauncherStubSourceFactory.State.closeCount.get(),
                    "second runShutdownSequence() must be a no-op");
        }

        @Test
        @DisplayName("two stub sources: closed in reverse declaration order (second declared, first closed)")
        void twoSourcesClosedInReverseDeclarationOrder() throws Exception {
            LauncherStubSourceFactory.State.values.put("db.password", SENTINEL);

            // Declare two sources: "first-stub" then "second-stub"
            String conf = "{\"config\":{\"propertySources\":["
                    + "{\"name\":\"first-stub\",\"type\":\"launcher-stub\"},"
                    + "{\"name\":\"second-stub\",\"type\":\"launcher-stub\"}"
                    + "]},"
                    + "\"db\":{\"password\":\"${db.password}\"}}";

            TestVertiqueApplication app = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf});
            int exitCode = app.launch();
            assertEquals(0, exitCode, "launch must succeed");

            // Close Vert.x directly (does NOT trigger hook pipeline)
            Vertx vertx = TestVertiqueApplication.capturedVertx.getAndSet(null);
            assertNotNull(vertx, "Vertx must have been captured");
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // runShutdownSequence() closes in reverse declaration order
            app.runShutdownSequence();
            assertEquals(2, LauncherStubSourceFactory.State.closeCount.get(), "both sources must be closed");

            List<String> closeOrder = List.copyOf(LauncherStubSourceFactory.State.closeLabels);
            assertEquals(2, closeOrder.size(), "close labels must have two entries");
            assertEquals("second-stub", closeOrder.get(0), "second-stub (declared last) must be closed first");
            assertEquals("first-stub", closeOrder.get(1), "first-stub (declared first) must be closed second");
        }
    }

    // --- Resolution failure path ---

    @Nested
    @DisplayName("resolution failure — unresolvable reference causes exit 11, verticle never starts")
    class ResolutionFailure {

        @Test
        @DisplayName("unresolvable reference: launch returns 11 and NoOpVerticle is never deployed")
        void unresolvableRefCausesExit11() {
            // No sources declared; ${no.such.key} cannot be resolved
            String conf = "{\"x\":\"${no.such.key}\"}";

            int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf}).launch();

            assertEquals(
                    ExitCodes.VERTX_INITIALIZATION,
                    exitCode,
                    "unresolvable placeholder must cause VERTX_INITIALIZATION (11)");
            // Verticle must never have been deployed
            assertEquals(null, NoOpVerticle.startedConfig.get(), "NoOpVerticle must never have started");
        }

        @Test
        @DisplayName("abort after a successful load still closes property sources (malformed vertx.options)")
        void abortAfterLoadClosesSources() {
            // The bootstrap load succeeds and creates the stub source. The vertx.options overlay
            // is now computed in beforeStartingVertx (after processVertxOptions), which fires
            // AFTER the eager BootstrapShutdown is already constructed in createVertxBuilder.
            // When the overlay fails (non-object section), startupFailure is stored and the
            // exception is rethrown; launch() then runs the shutdown via the already-built
            // BootstrapShutdown, which closes the property source.
            LauncherStubSourceFactory.State.values.put("db.password", "irrelevant");
            String conf = "{\"config\":{\"propertySources\":[{\"name\":\"stub\",\"type\":\"launcher-stub\"}]},"
                    + "\"vertx\":{\"options\":\"not-an-object\"}}";

            int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf}).launch();

            assertEquals(
                    ExitCodes.VERTX_INITIALIZATION,
                    exitCode,
                    "malformed vertx.options must cause VERTX_INITIALIZATION (11)");
            assertEquals(null, NoOpVerticle.startedConfig.get(), "NoOpVerticle must never have started");
            assertEquals(
                    1,
                    LauncherStubSourceFactory.State.closeCount.get(),
                    "the property source created by the successful load must be closed on the abort path");
        }
    }
}
