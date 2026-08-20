// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.security.RouteAuthHandler;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the stable, operator-visible bounds of {@link McpServerConfig}.
 *
 * <p>The numeric table below is derived from the frozen protocol/server-configuration contract: for
 * every bounded property it exercises the exact minimum and maximum (accepted) alongside
 * {@code minimum - 1} and {@code maximum + 1} (rejected), so a widened or narrowed bound cannot pass
 * unnoticed. {@code mcp.jsonProfile} rows are deliberately absent — profile resolution is owned by
 * {@code McpJsonProfileDefaultValidator} and is covered exhaustively by
 * {@code McpJsonProfileDefaultValidatorTest}; duplicating them here would pin the wrong owner.
 */
class McpServerConfigTest {
    private static final int MAX_INSTRUCTIONS_CHARS = 16_384;

    private final McpServerConfigValidator validator = new McpServerConfigValidator();

    @Test
    @DisplayName("accepts the programmatic defaults unchanged")
    void shouldAcceptProgrammaticDefaults() {
        McpServerConfig defaults = McpServerConfig.defaults();

        assertThatCode(() -> validator.validate(defaults)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(defaults, Set.of())).doesNotThrowAnyException();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.mountPath()).isEqualTo("/mcp/*");
        assertThat(defaults.allowedOrigins()).isEmpty();
        assertThat(defaults.jsonMaxDepth()).isEqualTo(64);
        assertThat(defaults.jsonMaxPropertiesPerObject()).isEqualTo(1_000);
        assertThat(defaults.jsonMaxItemsPerArray()).isEqualTo(10_000);
        assertThat(defaults.jsonMaxStringChars()).isEqualTo(262_144);
        assertThat(defaults.outputMaxBytes()).isEqualTo(2_097_152);
        assertThat(defaults.requestTimeoutMs()).isEqualTo(30_000L);
        assertThat(defaults.toolsPageSize()).isEqualTo(100);
        assertThat(defaults.toolsTtlMs()).isEqualTo(300_000L);
    }

    /**
     * Pins every bounded numeric property at its exact minimum and maximum, plus the adjacent
     * out-of-range value on each side.
     */
    @Nested
    @DisplayName("numeric bounds")
    class NumericBounds {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#acceptedNumericRows")
        @DisplayName("accepts a value at the documented bound")
        void shouldAcceptValueAtBound(String row, McpServerConfig configuration) {
            assertThatCode(() -> validator.validate(configuration)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#rejectedNumericRows")
        @DisplayName("rejects a value one step past the documented bound with the stable key")
        void shouldRejectValuePastBound(String row, McpServerConfig configuration, String key) {
            assertThatThrownBy(() -> validator.validate(configuration))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(key);
        }
    }

    /** Pins the identity and instruction bounds, which apply only while MCP is enabled. */
    @Nested
    @DisplayName("identity and instructions")
    class IdentityAndInstructions {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#acceptedIdentityRows")
        @DisplayName("accepts server identity and instructions within bounds")
        void shouldAcceptIdentity(String row, McpServerConfig configuration) {
            assertThatCode(() -> validator.validate(configuration)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#rejectedIdentityRows")
        @DisplayName("rejects missing identity and oversized instructions")
        void shouldRejectIdentity(String row, McpServerConfig configuration, String key) {
            assertThatThrownBy(() -> validator.validate(configuration))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(key);
        }
    }

    /**
     * Pins every optional-authentication combination the contract enumerates: an absent scheme, a
     * scheme matched by exactly one optional-capable handler, a scheme with no handler, a scheme
     * matched by two handlers, and a scheme whose handler lacks the optional capability.
     */
    @Nested
    @DisplayName("optional authentication")
    class OptionalAuthentication {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#acceptedAuthenticationRows")
        @DisplayName("accepts a resolvable optional-authentication binding")
        void shouldAcceptAuthenticationBinding(
                String row, McpServerConfig configuration, Set<RouteAuthHandler> handlers) {
            assertThatCode(() -> validator.validate(configuration, handlers)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#rejectedAuthenticationRows")
        @DisplayName("rejects an unresolvable optional-authentication binding")
        void shouldRejectAuthenticationBinding(
                String row, McpServerConfig configuration, Set<RouteAuthHandler> handlers) {
            assertThatThrownBy(() -> validator.validate(configuration, handlers))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("mcp.authenticationScheme");
        }
    }

    /** Pins the allowed-origin set, which must contain only non-blank exact origins. */
    @Nested
    @DisplayName("allowed origins")
    class AllowedOrigins {

        @Test
        @DisplayName("accepts an empty and an exact-origin set")
        void shouldAcceptExactOrigins() {
            assertThatCode(() -> validator.validate(
                            enabled().toBuilder().allowedOrigins(Set.of()).build()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(enabled().toBuilder()
                            .allowedOrigins(Set.of("https://example.test", "https://other.test"))
                            .build()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a blank origin")
        void shouldRejectBlankOrigin() {
            assertThatThrownBy(() -> validator.validate(
                            enabled().toBuilder().allowedOrigins(Set.of("  ")).build()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("mcp.allowedOrigins");
        }
    }

    /**
     * Pins {@code mcp.mountPath} against the frozen protocol/server-configuration contract: one
     * absolute, normalized, literal Router mount ending {@code /*} — leading {@code /}, no path
     * parameters ({@code :} segments), no wildcard other than the terminal {@code /*}, no query or
     * fragment, no duplicate separators, no {@code .}/{@code ..} segments, and no whitespace or
     * control characters.
     *
     * <p><strong>Ruling on the bare {@code /*} mount:</strong> it is accepted. The contract's rule
     * enumerates exactly the eight constraints above and none of them requires a named segment; the
     * default column supplies {@code /mcp/*} as a <em>default</em>, not as a shape constraint. A
     * bare {@code /*} is absolute, normalized, literal, and terminated by the sole wildcard, so it
     * satisfies every stated constraint and mounts MCP at the router root.
     */
    @Nested
    @DisplayName("mount path")
    class MountPath {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#acceptedMountPathRows")
        @DisplayName("accepts an absolute, normalized, literal mount")
        void shouldAcceptNormalizedLiteralMount(String row, String mountPath) {
            assertThatCode(() -> validator.validate(
                            enabled().toBuilder().mountPath(mountPath).build()))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#rejectedMountPathRows")
        @DisplayName("rejects any non-literal or non-normalized mount")
        void shouldRejectNonLiteralMount(String row, String mountPath) {
            assertThatThrownBy(() -> validator.validate(
                            enabled().toBuilder().mountPath(mountPath).build()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("mcp.mountPath");
        }
    }

    // --- Row tables: numeric bounds (W4) ---

    private static Stream<Arguments> acceptedNumericRows() {
        return numericProperties()
                .flatMap(property -> Stream.of(
                        Arguments.of(
                                property.key() + " at the minimum " + property.minimum(),
                                property.apply(property.minimum())),
                        Arguments.of(
                                property.key() + " at the maximum " + property.maximum(),
                                property.apply(property.maximum()))));
    }

    private static Stream<Arguments> rejectedNumericRows() {
        return numericProperties()
                .flatMap(property -> Stream.of(
                        Arguments.of(
                                property.key() + " one below the minimum (" + (property.minimum() - 1) + ")",
                                property.apply(property.minimum() - 1),
                                property.key()),
                        Arguments.of(
                                property.key() + " one above the maximum (" + (property.maximum() + 1) + ")",
                                property.apply(property.maximum() + 1),
                                property.key())));
    }

    /** The contract's bounded numeric properties, with the exact ranges the validator enforces. */
    private static Stream<NumericProperty> numericProperties() {
        return Stream.of(
                new NumericProperty(
                        "mcp.json.maxDepth", 8, 256, (builder, value) -> builder.jsonMaxDepth(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.json.maxPropertiesPerObject",
                        1,
                        10_000,
                        (builder, value) -> builder.jsonMaxPropertiesPerObject(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.json.maxItemsPerArray",
                        1,
                        100_000,
                        (builder, value) -> builder.jsonMaxItemsPerArray(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.json.maxStringChars",
                        1,
                        1_048_576,
                        (builder, value) -> builder.jsonMaxStringChars(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.output.maxBytes",
                        1_024,
                        16_777_216,
                        (builder, value) -> builder.outputMaxBytes(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.request.timeoutMs",
                        1_000,
                        1_800_000,
                        McpServerConfig.McpServerConfigBuilder::requestTimeoutMs),
                new NumericProperty(
                        "mcp.tools.pageSize",
                        1,
                        500,
                        (builder, value) -> builder.toolsPageSize(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.tools.ttlMs", 0, 3_600_000, McpServerConfig.McpServerConfigBuilder::toolsTtlMs));
    }

    // --- Row tables: identity and instructions (W4) ---

    private static Stream<Arguments> acceptedIdentityRows() {
        return Stream.of(
                Arguments.of("enabled with a server name and version", enabled()),
                Arguments.of(
                        "disabled with a blank server name",
                        enabled().toBuilder().enabled(false).serverName("  ").build()),
                Arguments.of(
                        "disabled with a blank server version",
                        enabled().toBuilder().enabled(false).serverVersion("  ").build()),
                Arguments.of(
                        "disabled with an absent server name and version",
                        McpServerConfig.builder().enabled(false).build()),
                Arguments.of("absent instructions", enabled()),
                Arguments.of(
                        "instructions at the 16,384-character bound",
                        enabled().toBuilder()
                                .instructions("i".repeat(MAX_INSTRUCTIONS_CHARS))
                                .build()));
    }

    private static Stream<Arguments> rejectedIdentityRows() {
        return Stream.of(
                Arguments.of(
                        "enabled with a blank server name",
                        enabled().toBuilder().serverName("  ").build(),
                        "mcp.serverName"),
                Arguments.of(
                        "enabled with an absent server name",
                        enabled().toBuilder().serverName(null).build(),
                        "mcp.serverName"),
                Arguments.of(
                        "enabled with a blank server version",
                        enabled().toBuilder().serverVersion("  ").build(),
                        "mcp.serverVersion"),
                Arguments.of(
                        "enabled with an absent server version",
                        enabled().toBuilder().serverVersion(null).build(),
                        "mcp.serverVersion"),
                Arguments.of(
                        "instructions one character past the 16,384-character bound",
                        enabled().toBuilder()
                                .instructions("i".repeat(MAX_INSTRUCTIONS_CHARS + 1))
                                .build(),
                        "mcp.instructions"),
                Arguments.of(
                        "a blank authentication scheme",
                        enabled().toBuilder().authenticationScheme("  ").build(),
                        "mcp.authenticationScheme"));
    }

    // --- Row tables: optional authentication (W4) ---

    private static Stream<Arguments> acceptedAuthenticationRows() {
        return Stream.of(
                Arguments.of("an absent scheme with no handlers", enabled(), Set.of()),
                Arguments.of(
                        "an absent scheme alongside an unrelated handler",
                        enabled(),
                        Set.of(authHandler("bearer", true))),
                Arguments.of(
                        "a scheme matched by exactly one optional-capable handler",
                        enabled().toBuilder().authenticationScheme("bearer").build(),
                        Set.of(authHandler("bearer", true), authHandler("apikey", true))),
                Arguments.of(
                        "a scheme on a disabled server with no handlers",
                        enabled().toBuilder()
                                .enabled(false)
                                .authenticationScheme("bearer")
                                .build(),
                        Set.of()));
    }

    private static Stream<Arguments> rejectedAuthenticationRows() {
        return Stream.of(
                Arguments.of(
                        "a scheme with no handlers at all",
                        enabled().toBuilder().authenticationScheme("bearer").build(),
                        Set.of()),
                Arguments.of(
                        "a scheme matched by no registered handler",
                        enabled().toBuilder().authenticationScheme("bearer").build(),
                        Set.of(authHandler("apikey", true))),
                Arguments.of(
                        "a scheme matched by two handlers",
                        enabled().toBuilder().authenticationScheme("bearer").build(),
                        Set.of(authHandler("bearer", true), authHandler("bearer", true))),
                Arguments.of(
                        "a scheme whose handler lacks the optional capability",
                        enabled().toBuilder().authenticationScheme("bearer").build(),
                        Set.of(authHandler("bearer", false))),
                Arguments.of(
                        "a blank scheme",
                        enabled().toBuilder().authenticationScheme("  ").build(),
                        Set.of(authHandler("bearer", true))));
    }

    // --- Row tables: mount path (W6) ---

    private static Stream<Arguments> acceptedMountPathRows() {
        return Stream.of(
                Arguments.of("the documented default", "/mcp/*"),
                Arguments.of("a nested literal mount", "/agents/mcp/*"),
                Arguments.of("the bare root mount", "/*"),
                Arguments.of("a segment containing separators legal in a literal", "/a-b_c.d/*"),
                Arguments.of("a deeply nested literal mount", "/a/b/c/d/*"));
    }

    private static Stream<Arguments> rejectedMountPathRows() {
        return Stream.of(
                Arguments.of("a relative mount", "mcp/*"),
                Arguments.of("a path-parameter segment", "/mcp/:tenant/*"),
                Arguments.of("a duplicate separator", "//mcp/*"),
                Arguments.of("an interior duplicate separator", "/mcp//sub/*"),
                Arguments.of("a parent dot segment", "/mcp/../*"),
                Arguments.of("a current dot segment", "/mcp/./*"),
                Arguments.of("a query string", "/mcp?x=1/*"),
                Arguments.of("a fragment", "/mcp#frag/*"),
                Arguments.of("embedded whitespace", "/m cp/*"),
                Arguments.of("a control character", "/m\u0001cp/*"),
                Arguments.of("a non-terminal wildcard", "/mcp/*/extra"),
                Arguments.of("an interior wildcard with a terminal wildcard", "/mcp/*/sub/*"),
                Arguments.of("a wildcard inside a segment", "/mc*p/*"),
                Arguments.of("no terminal wildcard", "/mcp"),
                Arguments.of("a bare trailing slash", "/mcp/"),
                Arguments.of("a wildcard without a separator", "/mcp*"),
                Arguments.of("an empty mount", ""),
                Arguments.of("a null mount", null));
    }

    // --- Fixtures ---

    private static McpServerConfig enabled() {
        return McpServerConfig.builder()
                .enabled(true)
                .serverName("server")
                .serverVersion("1.0")
                .build();
    }

    private static RouteAuthHandler authHandler(String schemeName, boolean optionalCapable) {
        return new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return schemeName;
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return RoutingContext::next;
            }

            @Override
            public Optional<Handler<RoutingContext>> createOptionalHandler() {
                return optionalCapable ? Optional.of(RoutingContext::next) : Optional.empty();
            }
        };
    }

    /** One bounded numeric configuration property and the builder mutator that sets it. */
    private record NumericProperty(
            String key,
            long minimum,
            long maximum,
            BiFunction<McpServerConfig.McpServerConfigBuilder, Long, McpServerConfig.McpServerConfigBuilder> mutator) {

        McpServerConfig apply(long value) {
            return mutator.apply(enabled().toBuilder(), value).build();
        }
    }
}
