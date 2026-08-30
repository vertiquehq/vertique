// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Verifies the {@code vertx.options} overlay behaviour in {@link VertiqueApplication}.
 *
 * <p>Unit-level tests ({@link UnitLevelTests}) call {@link VertxOptionsOverlay#apply} directly —
 * no Vert.x instance or launch required. They verify the identity contract: absent section →
 * same instance passthrough; present section → new instance with overlay values; precedence
 * order: base &lt; tree &lt; CLI &lt; sysprops. The per-key warn-and-skip for coercion failures
 * is also tested at the unit level ({@link UnitLevelTests#badSysPropKeySkippedGoodKeyApplied}).
 *
 * <p>Integration tests ({@link IntegrationTests}) go through the full launch pipeline to verify
 * that tree values, sysprop overrides, and CLI precedence are observable end-to-end via the
 * {@link VertiqueApplication#effectiveVertxOptions} test seam. Contributors receive the
 * <em>pre-overlay</em> options (the original instance, before the tree is merged), so
 * {@link TestContributorState#recordedEventLoopPoolSize} reflects the base (with CLI baked in)
 * rather than the tree-merged effective value.
 *
 * <p>The warn-and-skip integration test ({@link SysPropCoercionWarnSkipTests}) verifies the full
 * launch pipeline: a junk {@code vertx.options.*} sysprop does not abort startup when the
 * {@code vertx.options} tree section is present, the tree value is still applied, and a WARN is
 * emitted for the skipped key.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VertxOptionsOverlayTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoOpVerticle.class.getName();
    private static final String VERTX_OPT_PREFIX = "vertx.options.";

    // --- Unit-level pure-function tests ---

    @Nested
    @DisplayName("unit-level: VertxOptionsOverlay.apply() directly")
    class UnitLevelTests {

        @Test
        @DisplayName("absent vertx.options section: same VertxOptions instance returned unchanged")
        void absentSectionReturnsSameInstance() {
            VertxOptions options = new VertxOptions();
            // No vertx.options key in the tree
            VertxOptions result =
                    VertxOptionsOverlay.apply(options, new JsonObject(), new JsonObject(), new JsonObject());

            assertSame(
                    options, result, "when vertx.options is absent, the same VertxOptions instance must be returned");
        }

        @Test
        @DisplayName("present vertx.options section: new VertxOptions instance produced with tree values")
        void presentSectionProducesNewInstance() {
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put("vertx", new JsonObject().put("options", new JsonObject().put("eventLoopPoolSize", 7)));

            VertxOptions result = VertxOptionsOverlay.apply(options, tree, new JsonObject(), new JsonObject());

            assertNotSame(
                    options, result, "when vertx.options is present, a new VertxOptions instance must be produced");
            assertEquals(
                    7, result.getEventLoopPoolSize(), "the new instance must carry eventLoopPoolSize from the tree");
        }

        @Test
        @DisplayName("CLI wins over tree: cliOptionsJson takes precedence over vertx.options tree value")
        void cliWinsOverTree() {
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put("vertx", new JsonObject().put("options", new JsonObject().put("eventLoopPoolSize", 3)));
            JsonObject cli = new JsonObject().put("eventLoopPoolSize", 5);

            VertxOptions result = VertxOptionsOverlay.apply(options, tree, new JsonObject(), cli);

            assertEquals(
                    5, result.getEventLoopPoolSize(), "CLI eventLoopPoolSize=5 must beat tree eventLoopPoolSize=3");
        }

        @Test
        @DisplayName("sysprops win over CLI: sysPropsJson takes precedence over cliOptionsJson")
        void sysPropsWinOverCli() {
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put("vertx", new JsonObject().put("options", new JsonObject().put("eventLoopPoolSize", 3)));
            JsonObject cli = new JsonObject().put("eventLoopPoolSize", 5);
            // sysprops at highest precedence
            JsonObject sysProps = new JsonObject().put("eventLoopPoolSize", 9L);

            VertxOptions result = VertxOptionsOverlay.apply(options, tree, sysProps, cli);

            assertEquals(
                    9, result.getEventLoopPoolSize(), "sysPropsJson eventLoopPoolSize=9 must beat CLI=5 and tree=3");
        }

        @Test
        @DisplayName("sysprops win over tree when CLI is absent")
        void sysPropsWinOverTree() {
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put("vertx", new JsonObject().put("options", new JsonObject().put("eventLoopPoolSize", 3)));
            JsonObject sysProps = new JsonObject().put("eventLoopPoolSize", 5L);

            VertxOptions result = VertxOptionsOverlay.apply(options, tree, sysProps, new JsonObject());

            assertEquals(
                    5,
                    result.getEventLoopPoolSize(),
                    "sysPropsJson eventLoopPoolSize=5 must beat tree eventLoopPoolSize=3");
        }

        @Test
        @DisplayName("bad sysprop key warn-and-skip: junk value skipped; valid sysprop key still applied")
        void badSysPropKeySkippedGoodKeyApplied() {
            // maxWorkerExecuteTimeUnit=NOT_A_UNIT causes VertxOptionsConverter to throw
            // IllegalArgumentException (unknown TimeUnit enum constant). It must be skipped.
            // A valid key (eventLoopPoolSize=5) in the same sysPropsJson must still be applied.
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put("vertx", new JsonObject().put("options", new JsonObject().put("workerPoolSize", 2)));
            // sysPropsJson contains both a valid key and a junk key
            JsonObject sysProps =
                    new JsonObject().put("eventLoopPoolSize", 5L).put("maxWorkerExecuteTimeUnit", "NOT_A_UNIT");

            VertxOptions result = VertxOptionsOverlay.apply(options, tree, sysProps, new JsonObject());

            assertEquals(
                    5,
                    result.getEventLoopPoolSize(),
                    "Valid sysprop key eventLoopPoolSize=5 must be applied despite junk sibling key");
            assertEquals(
                    2,
                    result.getWorkerPoolSize(),
                    "Tree workerPoolSize=2 must still be applied when a sibling sysprop key is skipped");
        }

        @Test
        @DisplayName("malformed TREE value still aborts even when a valid sysprop covers the same key")
        void malformedTreeValueNotMaskedBySysProp() {
            // The tree/CLI layer is fail-fast: a valid sysprop for the same key must NOT mask a
            // malformed tree value, and sibling sysprops must not be misattributed-skipped.
            VertxOptions options = new VertxOptions();
            JsonObject tree = new JsonObject()
                    .put(
                            "vertx",
                            new JsonObject().put("options", new JsonObject().put("maxWorkerExecuteTimeUnit", "BAD")));
            JsonObject sysProps = new JsonObject().put("maxWorkerExecuteTimeUnit", "SECONDS");

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> VertxOptionsOverlay.apply(options, tree, sysProps, new JsonObject()),
                    "a malformed tree value must abort startup even when a sysprop would repair it");
        }
    }

    // --- Integration tests ---

    @Nested
    @DisplayName("integration: full launch pipeline via effectiveVertxOptions seam")
    class IntegrationTests {

        /** Clears {@code vertx.options.*} system properties set during tests. */
        @AfterEach
        void clearVertxOptionsProps() {
            System.clearProperty(VERTX_OPT_PREFIX + "eventLoopPoolSize");
            System.clearProperty(VERTX_OPT_PREFIX + "workerPoolSize");
        }

        @Test
        @DisplayName("tree key observable: vertx.options.eventLoopPoolSize=3 lands in effectiveVertxOptions")
        void treeKeyReachesEffectiveOptions() {
            TestVertiqueApplication app = new TestVertiqueApplication(
                    new String[] {NOOP_VERTICLE, "--conf", "{\"vertx\":{\"options\":{\"eventLoopPoolSize\":3}}}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set after launch");
            assertEquals(
                    3,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "effectiveVertxOptions must carry eventLoopPoolSize=3 from vertx.options tree");
        }

        @Test
        @DisplayName("contributors see pre-overlay options: tree eventLoopPoolSize=3 not visible to contributor;"
                + " effectiveVertxOptions carries it")
        void contributorSeesPreOverlayOptions() {
            TestVertiqueApplication app = new TestVertiqueApplication(
                    new String[] {NOOP_VERTICLE, "--conf", "{\"vertx\":{\"options\":{\"eventLoopPoolSize\":3}}}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            // Contributor sees the PRE-overlay options (original, no tree applied)
            assertEquals(
                    new VertxOptions().getEventLoopPoolSize(),
                    TestContributorState.recordedEventLoopPoolSize.get(),
                    "contributor must see default eventLoopPoolSize (pre-overlay, tree not yet applied)");
            // Effective options carry the tree value
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set");
            assertEquals(
                    3,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "effectiveVertxOptions must reflect the tree overlay value");
        }

        @Test
        @DisplayName("CLI wins over tree: --options eventLoopPoolSize=5 beats tree=3 in effectiveVertxOptions")
        void cliBeatsTree() {
            TestVertiqueApplication app = new TestVertiqueApplication(new String[] {
                NOOP_VERTICLE,
                "--options",
                "{\"eventLoopPoolSize\":5}",
                "--conf",
                "{\"vertx\":{\"options\":{\"eventLoopPoolSize\":3}}}"
            });
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set");
            assertEquals(
                    5,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "CLI --options eventLoopPoolSize=5 must beat tree eventLoopPoolSize=3");
        }

        @Test
        @DisplayName("sysprop wins over tree: vertx.options.eventLoopPoolSize=5 beats tree=3")
        void sysPropWinsOverTree() {
            System.setProperty(VERTX_OPT_PREFIX + "eventLoopPoolSize", "5");

            TestVertiqueApplication app = new TestVertiqueApplication(
                    new String[] {NOOP_VERTICLE, "--conf", "{\"vertx\":{\"options\":{\"eventLoopPoolSize\":3}}}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set");
            assertEquals(
                    5,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "sysprop vertx.options.eventLoopPoolSize=5 must beat tree eventLoopPoolSize=3");
        }

        @Test
        @DisplayName("sysprop and tree both land: workerPoolSize=7 from sysprop + eventLoopPoolSize=3 from tree"
                + " — both visible in effectiveVertxOptions")
        void sysPropAndTreeBothLand() {
            // This is the DEFECT pinning test: sysprop must survive even when the tree overlay
            // re-binds the builder. Both workerPoolSize (from sysprop) and eventLoopPoolSize
            // (from tree) must land in the effective options.
            System.setProperty(VERTX_OPT_PREFIX + "workerPoolSize", "7");

            TestVertiqueApplication app = new TestVertiqueApplication(
                    new String[] {NOOP_VERTICLE, "--conf", "{\"vertx\":{\"options\":{\"eventLoopPoolSize\":3}}}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set after launch");
            assertEquals(
                    3,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "effectiveVertxOptions must carry eventLoopPoolSize=3 from tree");
            assertEquals(
                    7,
                    app.effectiveVertxOptions.getWorkerPoolSize(),
                    "effectiveVertxOptions must carry workerPoolSize=7 from sysprop"
                            + " (sysprop must not be lost when tree overlay re-binds builder)");
        }

        @Test
        @DisplayName("zero-overlay: absent vertx.options section — effectiveVertxOptions is the same instance as base")
        void zeroOverlaySameInstance() {
            // No vertx.options tree section — same-instance passthrough contract
            TestVertiqueApplication app =
                    new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", "{\"hello\":\"world\"}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "launch must succeed");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set");
            // The contributor sees the SAME instance as effectiveVertxOptions (no overlay applied)
            assertSame(
                    TestContributorState.recordedVertxOptions.get(),
                    app.effectiveVertxOptions,
                    "without a vertx.options tree section, effectiveVertxOptions must be the same"
                            + " instance the contributor received");
        }
    }

    // --- Sysprop coercion warn-and-skip integration tests ---

    /**
     * Verifies the warn-and-skip behaviour for {@code vertx.options.*} system properties that
     * produce a coercion error when applied to {@link VertxOptions}.
     *
     * <p>When the {@code vertx.options} tree section is present and a {@code vertx.options.*}
     * sysprop has a value that causes {@link IllegalArgumentException} during the tentative
     * per-key {@link VertxOptions} construction (e.g. an unknown enum constant), the key is
     * skipped with a WARN log and startup is NOT aborted.
     *
     * <p>Uses a {@link ListAppender} to capture the WARN message emitted by
     * {@link VertxOptionsOverlay} for the skipped key.
     */
    @Nested
    @DisplayName("sysprop coercion warn-and-skip: junk vertx.options.* sysprop skipped; startup succeeds")
    class SysPropCoercionWarnSkipTests {

        /** The junk sysprop key used in these tests. */
        private static final String JUNK_KEY = VERTX_OPT_PREFIX + "maxWorkerExecuteTimeUnit";

        /** Logback appender that captures log events during the test. */
        private ListAppender<ILoggingEvent> listAppender;

        /** Reference to the ROOT logger for appender attach/detach. */
        private Logger rootLogger;

        /** Prior root logger level, restored after each test. */
        private Level savedLevel;

        /**
         * Attaches a capturing log appender to the root logger before each test.
         * Forces the level to at least WARN so the sysprop-skip warning is captured.
         */
        @BeforeEach
        void attachAppender() {
            rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            savedLevel = rootLogger.getLevel();
            if (rootLogger.getLevel() == null || rootLogger.getLevel().isGreaterOrEqual(Level.ERROR)) {
                rootLogger.setLevel(Level.WARN);
            }
            listAppender = new ListAppender<>();
            listAppender.setContext(rootLogger.getLoggerContext());
            listAppender.start();
            rootLogger.addAppender(listAppender);
        }

        /**
         * Detaches and stops the capturing appender and restores the root logger level.
         */
        @AfterEach
        void detachAppender() {
            System.clearProperty(JUNK_KEY);
            System.clearProperty(VERTX_OPT_PREFIX + "eventLoopPoolSize");
            if (rootLogger != null && listAppender != null) {
                rootLogger.detachAppender(listAppender);
                listAppender.stop();
            }
            if (rootLogger != null && savedLevel != null) {
                rootLogger.setLevel(savedLevel);
            }
        }

        @Test
        @DisplayName("tree section present + junk vertx.options.maxWorkerExecuteTimeUnit=NOT_A_UNIT "
                + "→ startup succeeds, tree value applied, junk key skipped, WARN logged")
        void junkSysPropSkippedOnTreeSectionPresent() {
            // Set both a valid sysprop override and a junk one.
            // The tree section (eventLoopPoolSize=3) must still apply.
            // The valid sysprop (workerPoolSize=7) must still apply.
            // The junk sysprop (maxWorkerExecuteTimeUnit=NOT_A_UNIT) must be skipped with WARN.
            System.setProperty(JUNK_KEY, "NOT_A_UNIT");
            System.setProperty(VERTX_OPT_PREFIX + "eventLoopPoolSize", "5");

            TestVertiqueApplication app = new TestVertiqueApplication(
                    new String[] {NOOP_VERTICLE, "--conf", "{\"vertx\":{\"options\":{\"workerPoolSize\":3}}}"});
            int exitCode = app.launch();

            assertEquals(0, exitCode, "startup must succeed despite junk vertx.options.* sysprop");
            assertNotNull(app.effectiveVertxOptions, "effectiveVertxOptions must be set");
            assertEquals(
                    3,
                    app.effectiveVertxOptions.getWorkerPoolSize(),
                    "tree workerPoolSize=3 must still be applied when junk sysprop is skipped");
            assertEquals(
                    5,
                    app.effectiveVertxOptions.getEventLoopPoolSize(),
                    "valid sysprop eventLoopPoolSize=5 must be applied alongside the skipped junk key");

            // Verify WARN log was emitted for the skipped key
            String warnLogs = listAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(warnLogs.isEmpty(), "a WARN must have been logged for the skipped junk sysprop key");
            assertTrue(
                    warnLogs.contains("maxWorkerExecuteTimeUnit"),
                    "WARN must name the skipped property key: " + warnLogs);
        }
    }
}
