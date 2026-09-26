// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.config.JaxRsSecurityConfig;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * TP-001 (T005): {@code RestCoreModule.jaxRsConfig}'s {@code jaxrs.security} section defaults off,
 * parses the opt-in, announces it with one INFO line when enabled, and rejects an unknown or
 * misplaced key before parsing — never echoing a configuration value.
 *
 * <p>Each row runs the provider directly, in its own package, with the framework's real {@link
 * dev.vertique.core.config.ConfigParser} (as {@code JwtAuthConfigTest} does), and captures the
 * provider logger's INFO events with a Logback {@link ListAppender} (as {@code
 * MountVerifierWarnTest} does for the mount logger).
 */
class JaxRsSecurityConfigProviderTest {

    private static final org.slf4j.Logger TEST_LOG = LoggerFactory.getLogger(JaxRsSecurityConfigProviderTest.class);

    private static final String SENTINEL_C = "sentinel-c-93af0d7e";

    private static final String INFO_MESSAGE = "jaxrs.security.requireExplicitPolicy is enabled: "
            + "explicit security policies are required for every JAX-RS operation";

    private Logger moduleLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureModuleLogs() {
        moduleLogger = (Logger) LoggerFactory.getLogger(RestCoreModule.class);
        previousLevel = moduleLogger.getLevel();
        moduleLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        moduleLogger.addAppender(appender);
    }

    @AfterEach
    void releaseModuleLogs() {
        moduleLogger.detachAppender(appender);
        appender.stop();
        moduleLogger.setLevel(previousLevel);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("jaxrs.security defaults off, is announced when on, and rejects unknown or misplaced keys")
    void securitySectionDefaultsParsesAndRejectsUnknownKeys(Case testCase) {
        Outcome outcome =
                switch (testCase.kind()) {
                    case PROVIDER -> runProviderCase(testCase.json());
                    case BUILDER -> new Outcome(JaxRsConfig.builder().build().security(), null, List.of());
                    case DEFAULTS -> new Outcome(JaxRsSecurityConfig.defaults(), null, List.of());
                };
        TEST_LOG.info(
                "TP-001 {}: failure={} security={} info={}",
                testCase.name(),
                outcome.failure() != null ? outcome.failure().getMessage() : null,
                outcome.security(),
                outcome.infoMessages());
        testCase.assertion().accept(outcome);
    }

    // --- Cases ---

    private static Stream<Case> cases() {
        return Stream.of(
                new Case("(a) no jaxrs.security section", Kind.PROVIDER, """
                        {"jaxrs":{}}
                        """, outcome -> {
                    assertSuccess(outcome, false);
                    assertNoInfo(outcome);
                }),
                new Case("(b) opt-in explicitly true", Kind.PROVIDER, """
                        {"jaxrs":{"security":{"requireExplicitPolicy":true}}}
                        """, outcome -> {
                    assertSuccess(outcome, true);
                    assertEquals(List.of(INFO_MESSAGE), outcome.infoMessages());
                }),
                new Case(
                        "(c) misspelled key carrying a sentinel value",
                        Kind.PROVIDER,
                        """
                        {"jaxrs":{"security":{"requireExplicitPolicy":true,"requireExplictPolicy":"%s"}}}
                        """.formatted(SENTINEL_C),
                        outcome -> {
                            assertFailureExact(outcome, "Unknown keys under 'jaxrs.security': 'requireExplictPolicy'");
                            assertNoValueLeak(outcome, SENTINEL_C);
                            assertNoInfo(outcome);
                        }),
                new Case("(d) security is not an object (boolean)", Kind.PROVIDER, """
                        {"jaxrs":{"security":true}}
                        """, outcome -> {
                    assertFailureExact(outcome, "'jaxrs.security' must be a JSON object");
                    assertNoValueLeak(outcome, "true");
                    assertNoInfo(outcome);
                }),
                new Case("(e) empty security section", Kind.PROVIDER, """
                        {"jaxrs":{"security":{}}}
                        """, outcome -> {
                    assertSuccess(outcome, false);
                    assertNoInfo(outcome);
                }),
                new Case("(f) two unknown keys, sorted alpha before zeta", Kind.PROVIDER, """
                        {"jaxrs":{"security":{"zeta":1,"alpha":2}}}
                        """, outcome -> {
                    assertFailureExact(outcome, "Unknown keys under 'jaxrs.security': 'alpha', 'zeta'");
                    assertNoInfo(outcome);
                }),
                new Case("(g) null security section", Kind.PROVIDER, """
                        {"jaxrs":{"security":null}}
                        """, outcome -> {
                    assertFailureExact(outcome, "'jaxrs.security' must be a JSON object");
                    assertNoInfo(outcome);
                }),
                new Case("(h) case variant of the section name", Kind.PROVIDER, """
                        {"jaxrs":{"Security":{"requireExplicitPolicy":true}}}
                        """, outcome -> {
                    assertFailureExact(
                            outcome,
                            "Misplaced or miscased security keys under 'jaxrs': 'Security'; "
                                    + "security settings belong under 'jaxrs.security'");
                    assertNoInfo(outcome);
                }),
                new Case("(i) requireExplicitPolicy misplaced at the jaxrs level", Kind.PROVIDER, """
                        {"jaxrs":{"requireExplicitPolicy":true}}
                        """, outcome -> {
                    assertFailureExact(
                            outcome,
                            "Misplaced or miscased security keys under 'jaxrs': 'requireExplicitPolicy'; "
                                    + "security settings belong under 'jaxrs.security'");
                    assertNoValueLeak(outcome, "true");
                    assertNoInfo(outcome);
                }),
                new Case("(j) opt-in explicitly false", Kind.PROVIDER, """
                        {"jaxrs":{"security":{"requireExplicitPolicy":false}}}
                        """, outcome -> {
                    assertSuccess(outcome, false);
                    assertNoInfo(outcome);
                }),
                new Case("(k) misplaced key with different case (PP4-001)", Kind.PROVIDER, """
                        {"jaxrs":{"RequireExplicitPolicy":true}}
                        """, outcome -> {
                    assertFailureExact(
                            outcome,
                            "Misplaced or miscased security keys under 'jaxrs': 'RequireExplicitPolicy'; "
                                    + "security settings belong under 'jaxrs.security'");
                    assertNoValueLeak(outcome, "true");
                    assertNoInfo(outcome);
                }),
                new Case("builder — JaxRsConfig.builder().build().security()", Kind.BUILDER, null, outcome -> {
                    assertNotNull(outcome.security());
                    assertFalse(outcome.security().requireExplicitPolicy());
                }),
                new Case("defaults — JaxRsSecurityConfig.defaults()", Kind.DEFAULTS, null, outcome -> {
                    assertNotNull(outcome.security());
                    assertFalse(outcome.security().requireExplicitPolicy());
                }));
    }

    // --- TP-002 (G2-13) ---

    /**
     * Guards {@code RestCoreModule#checkSecurityKeys}'s reserved-name rule: it rejects any raw
     * {@code "jaxrs"}-level key that case-insensitively matches a {@link JaxRsSecurityConfig}
     * record component name, regardless of whether that key is actually a legitimate {@link
     * JaxRsConfig} field. A future {@link JaxRsSecurityConfig} component sharing a name,
     * case-insensitively, with an existing {@link JaxRsConfig} field would silently make that
     * field unreachable from raw config. {@code "security"} itself is the expected container key
     * and is excluded from the {@link JaxRsConfig} side of the comparison.
     */
    @Test
    @DisplayName("JaxRsSecurityConfig component names never collide, case-insensitively, with a JaxRsConfig key")
    void securityComponentNamesDoNotCollideWithJaxRsConfigKeys() {
        Set<String> securityComponentNames = Arrays.stream(JaxRsSecurityConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> jaxRsConfigKeys = Arrays.stream(JaxRsConfig.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .filter(name -> !name.equals("security"))
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> collisions = new TreeSet<>(securityComponentNames);
        collisions.retainAll(jaxRsConfigKeys);

        assertTrue(
                collisions.isEmpty(),
                () -> "JaxRsSecurityConfig component name(s) " + collisions + " collide, case-insensitively, with a "
                        + "JaxRsConfig key; such a key would become unreachable via the reserved-name raw-key check "
                        + "(RestCoreModule#checkSecurityKeys)");
    }

    // --- Helpers ---

    private Outcome runProviderCase(String json) {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());
        JsonObject appConfig = new JsonObject(json);
        try {
            JaxRsConfig config = RestCoreModule.jaxRsConfig(appConfig, parser);
            return new Outcome(config.security(), null, capturedInfoMessages());
        } catch (RuntimeException e) {
            return new Outcome(null, e, capturedInfoMessages());
        }
    }

    private List<String> capturedInfoMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getLoggerName().equals(RestCoreModule.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void assertSuccess(Outcome outcome, boolean expectedValue) {
        assertNull(
                outcome.failure(),
                "expected no failure but got: "
                        + (outcome.failure() != null ? outcome.failure().getMessage() : null));
        assertNotNull(outcome.security());
        assertEquals(expectedValue, outcome.security().requireExplicitPolicy());
    }

    private static void assertNoInfo(Outcome outcome) {
        assertTrue(outcome.infoMessages().isEmpty(), "expected no INFO events but got: " + outcome.infoMessages());
    }

    private static void assertFailureExact(Outcome outcome, String expectedMessage) {
        assertNotNull(
                outcome.failure(),
                "expected a ConfigurationException naming the offending key(s) but the provider returned: "
                        + outcome.security());
        assertInstanceOf(ConfigurationException.class, outcome.failure());
        assertEquals(expectedMessage, outcome.failure().getMessage());
    }

    private static void assertNoValueLeak(Outcome outcome, String... values) {
        String message = outcome.failure() != null ? outcome.failure().getMessage() : "";
        for (String value : values) {
            assertFalse(
                    message.contains(value),
                    "message must not echo the configuration value '" + value + "': " + message);
        }
    }

    // --- Fixtures ---

    private enum Kind {
        PROVIDER,
        BUILDER,
        DEFAULTS
    }

    private record Case(String name, Kind kind, @Nullable String json, Consumer<Outcome> assertion) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Outcome(
            @Nullable JaxRsSecurityConfig security,
            @Nullable RuntimeException failure,
            List<String> infoMessages) {}
}
