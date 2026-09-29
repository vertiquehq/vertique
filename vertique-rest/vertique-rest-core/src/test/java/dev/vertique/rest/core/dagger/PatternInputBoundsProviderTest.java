// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import io.vertx.core.json.JsonObject;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-008 (rest-022 T004): {@code RestCoreModule.jaxRsConfig} validates the two pattern-input bounds of the
 * {@code web-validation} gate immediately after parsing. A per-string limit below 1 fails naming {@code
 * jaxrs.validationPatternMaxChars}; otherwise a total below 1 or below the per-string limit fails naming
 * {@code jaxrs.validationPatternMaxTotalChars}; valid limits, including the edges, are returned as
 * configured, and an absent {@code jaxrs} section yields the defaults.
 *
 * <p>Each row calls the provider directly, in its own package, with the framework's real {@link
 * ConfigParser}, as {@code JaxRsSecurityConfigProviderTest} does.
 */
class PatternInputBoundsProviderTest {

    /** The per-string setting's stable config path. */
    private static final String MAX_CHARS = "jaxrs.validationPatternMaxChars";

    /** The per-request setting's stable config path. */
    private static final String MAX_TOTAL_CHARS = "jaxrs.validationPatternMaxTotalChars";

    /**
     * TP-008. Invalid limits fail at the {@code JaxRsConfig} provider with a {@link ConfigurationException}
     * naming the failing setting and not the other; accepted limits are returned exactly.
     *
     * @param row the row's configuration and expectation
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    @DisplayName("TP-008: invalid pattern-input limits fail at the JaxRsConfig provider, naming the setting")
    void invalidLimitsFailAtTheJaxRsConfigProvider(Limits row) {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());
        JsonObject appConfig = new JsonObject(row.config());

        if (row.failingSetting() != null) {
            ConfigurationException failure = assertThrows(
                    ConfigurationException.class,
                    () -> RestCoreModule.jaxRsConfig(appConfig, parser),
                    "the provider must reject the limits");
            String otherSetting = MAX_CHARS.equals(row.failingSetting()) ? MAX_TOTAL_CHARS : MAX_CHARS;
            assertAll(
                    () -> assertTrue(
                            failure.getMessage().contains(row.failingSetting()),
                            () -> "the message must name " + row.failingSetting() + ": " + failure.getMessage()),
                    () -> assertFalse(
                            failure.getMessage().contains(otherSetting),
                            () -> "the message must not name " + otherSetting + ": " + failure.getMessage()));
        } else {
            JaxRsConfig config = assertDoesNotThrow(
                    () -> RestCoreModule.jaxRsConfig(appConfig, parser), "the provider must accept the limits");
            assertAll(
                    () -> assertEquals(row.maxChars(), config.validationPatternMaxChars(), MAX_CHARS),
                    () -> assertEquals(row.maxTotalChars(), config.validationPatternMaxTotalChars(), MAX_TOTAL_CHARS));
        }
    }

    /**
     * TP-008's rows: the invalid configurations, then the accepted edges.
     *
     * @return the rows
     */
    static Stream<Named<Limits>> limits() {
        return Stream.of(
                rejected("per-string 0", "{\"jaxrs\":{\"validationPatternMaxChars\":0}}", MAX_CHARS),
                rejected("per-string -1", "{\"jaxrs\":{\"validationPatternMaxChars\":-1}}", MAX_CHARS),
                rejected("total 0", "{\"jaxrs\":{\"validationPatternMaxTotalChars\":0}}", MAX_TOTAL_CHARS),
                rejected(
                        "total 4095 with the default per-string",
                        "{\"jaxrs\":{\"validationPatternMaxTotalChars\":4095}}",
                        MAX_TOTAL_CHARS),
                rejected(
                        "per-string 0 and total 0 together",
                        "{\"jaxrs\":{\"validationPatternMaxChars\":0,\"validationPatternMaxTotalChars\":0}}",
                        MAX_CHARS),
                accepted(
                        "accepted edge: per-string 1 with total 1",
                        "{\"jaxrs\":{\"validationPatternMaxChars\":1,\"validationPatternMaxTotalChars\":1}}",
                        1,
                        1),
                accepted(
                        "accepted edge: per-string 4096 with total 4096",
                        "{\"jaxrs\":{\"validationPatternMaxChars\":4096,\"validationPatternMaxTotalChars\":4096}}",
                        4096,
                        4096),
                accepted("accepted edge: no jaxrs section at all yields the defaults", "{}", 4096, 262_144));
    }

    /**
     * A row the provider must reject.
     *
     * @param label          the row label
     * @param config         the application configuration
     * @param failingSetting the setting the message must name
     * @return the row
     */
    private static Named<Limits> rejected(String label, String config, String failingSetting) {
        return Named.of(label, new Limits(config, failingSetting, 0, 0));
    }

    /**
     * A row the provider must accept.
     *
     * @param label         the row label
     * @param config        the application configuration
     * @param maxChars      the expected per-string limit
     * @param maxTotalChars the expected per-request limit
     * @return the row
     */
    private static Named<Limits> accepted(String label, String config, int maxChars, int maxTotalChars) {
        return Named.of(label, new Limits(config, null, maxChars, maxTotalChars));
    }

    /**
     * One TP-008 row.
     *
     * @param config         the application configuration, as JSON
     * @param failingSetting the setting a rejection must name, or {@code null} for an accepted row
     * @param maxChars       the per-string limit an accepted row returns
     * @param maxTotalChars  the per-request limit an accepted row returns
     */
    private record Limits(String config, String failingSetting, int maxChars, int maxTotalChars) {}
}
