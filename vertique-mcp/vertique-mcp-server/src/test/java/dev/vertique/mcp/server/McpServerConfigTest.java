// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.RouteAuthHandler;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
@Timeout(value = 20, unit = TimeUnit.SECONDS)
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
        assertThat(defaults.outputMaxBytes()).isEqualTo(2_097_152);
        assertThat(defaults.toolsPageSize()).isEqualTo(100);
        assertThat(defaults.toolsTtlMs()).isEqualTo(300_000L);
    }

    /**
     * R17 — binds the two independently configurable parser-token budgets through the production
     * configuration mapper. The flat property names are deliberate: nested {@code ingress.maxTokens}
     * and {@code output.maxTokens} describe no supported MCP configuration shape and must remain
     * ordinary lenient unknown properties rather than silently configuring either budget.
     */
    @Test
    @DisplayName("binds and validates independent JSON token budgets through the production mapper")
    void shouldBindAndValidateIndependentTokenBudgetsThroughTheProductionMapper() {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

        // Given: an omitted budget pair binds the two documented defaults.
        McpServerConfig defaults = parser.parse(new JsonObject(), McpServerConfig.class);

        assertThat(defaults.ingressMaxTokens()).isEqualTo(65_536);
        assertThat(defaults.outputMaxTokens()).isEqualTo(65_536);
        assertThatCode(() -> validator.validate(defaults)).doesNotThrowAnyException();

        // When: each flat property is supplied at both inclusive boundaries by itself.
        McpServerConfig ingressMinimum =
                parser.parse(new JsonObject().put("ingressMaxTokens", 1_024), McpServerConfig.class);
        McpServerConfig ingressMaximum =
                parser.parse(new JsonObject().put("ingressMaxTokens", 262_144), McpServerConfig.class);
        McpServerConfig outputMinimum =
                parser.parse(new JsonObject().put("outputMaxTokens", 1_024), McpServerConfig.class);
        McpServerConfig outputMaximum =
                parser.parse(new JsonObject().put("outputMaxTokens", 262_144), McpServerConfig.class);

        // Then: every boundary is accepted, and changing either setting leaves the other unchanged.
        assertThatCode(() -> validator.validate(ingressMinimum)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(ingressMaximum)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(outputMinimum)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(outputMaximum)).doesNotThrowAnyException();
        assertThat(ingressMinimum.ingressMaxTokens()).isEqualTo(1_024);
        assertThat(ingressMaximum.ingressMaxTokens()).isEqualTo(262_144);
        assertThat(outputMinimum.outputMaxTokens()).isEqualTo(1_024);
        assertThat(outputMaximum.outputMaxTokens()).isEqualTo(262_144);
        assertThat(ingressMinimum.outputMaxTokens()).isEqualTo(65_536);
        assertThat(ingressMaximum.outputMaxTokens()).isEqualTo(65_536);
        assertThat(outputMinimum.ingressMaxTokens()).isEqualTo(65_536);
        assertThat(outputMaximum.ingressMaxTokens()).isEqualTo(65_536);

        // Adjacent out-of-range values fail independently with stable, value-free flat-key/range messages.
        assertAll(
                () -> assertTokenBudgetIsRejected(parser, "ingressMaxTokens", 1_023, "mcp.ingressMaxTokens"),
                () -> assertTokenBudgetIsRejected(parser, "ingressMaxTokens", 262_145, "mcp.ingressMaxTokens"),
                () -> assertTokenBudgetIsRejected(parser, "outputMaxTokens", 1_023, "mcp.outputMaxTokens"),
                () -> assertTokenBudgetIsRejected(parser, "outputMaxTokens", 262_145, "mcp.outputMaxTokens"));

        // Nested dotted spellings are not aliases for the two intentionally flat MCP properties.
        McpServerConfig nestedSpellings = parser.parse(
                new JsonObject()
                        .put("ingress", new JsonObject().put("maxTokens", 1_024))
                        .put("output", new JsonObject().put("maxTokens", 262_144)),
                McpServerConfig.class);

        assertThat(nestedSpellings.ingressMaxTokens()).isEqualTo(65_536);
        assertThat(nestedSpellings.outputMaxTokens()).isEqualTo(65_536);
        assertThatCode(() -> validator.validate(nestedSpellings)).doesNotThrowAnyException();
    }

    /**
     * R43 — the six implementation-era spellings ({@code requestTimeoutMs}, {@code jsonMaxDepth},
     * {@code jsonMaxPropertiesPerObject}, {@code jsonMaxItemsPerArray}, {@code jsonMaxStringChars},
     * and {@code toolsListDeadlineMs}) never shipped in any release and carried a one-release
     * rejection machinery (issue #424, R02 TP-002) that protected an installed base of zero. R43
     * removed that machinery: every one of these spellings is now an ordinary unrecognized property
     * that falls into {@link McpServerConfig}'s class-level {@code ignoreUnknown = true}
     * forward-compatible path exactly like any other unknown key.
     *
     * <p>Every row below is driven through {@link McpServerConfigUnknownKeyTestFixture#configParser()},
     * the real {@code dev.vertique.config.parser.DefaultConfigParser} over the real lenient
     * {@code dev.vertique.config.parser.DefaultConfigMapper} — the same class every other Dagger
     * boundary provider (for example {@code RestCoreModule#httpConfig}) injects to parse a config
     * section in production — so this proves what the <strong>production</strong> configuration
     * loader does with a key it does not recognize, not merely what a test-local mapper would do.
     */
    @Nested
    @DisplayName("unknown and formerly retired configuration keys (R43)")
    class UnknownAndFormerlyRetiredKeys {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#formerlyRetiredKeys")
        @DisplayName("silently ignores a formerly retired key spelling through the production mapper")
        void shouldSilentlyIgnoreAFormerlyRetiredKeyThroughTheProductionMapper(String formerlyRetiredKey) {
            McpServerConfig loaded = McpServerConfigUnknownKeyTestFixture.parse(Set.of(formerlyRetiredKey));

            assertThatCode(() -> validator.validate(loaded))
                    .as("a formerly retired key must compose and validate like any other unknown key")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an ordinary unknown key stays forward-compatible through the production mapper")
        void shouldIgnoreAnOrdinaryUnknownKeyThroughTheProductionMapper() {
            McpServerConfig loaded = McpServerConfigUnknownKeyTestFixture.parseWithOrdinaryUnknownKey();

            assertThatCode(() -> validator.validate(loaded))
                    .as("a config carrying only an ordinary unknown key must still bind and validate")
                    .doesNotThrowAnyException();
            assertThat(loaded.outputMaxBytes())
                    .as("a retained bounded key supplied alongside the ordinary unknown key still binds")
                    .isEqualTo(McpServerConfigUnknownKeyTestFixture.RETAINED_OUTPUT_MAX_BYTES);
        }
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
     * Pins the P04 startup liveness gate (issue W1): an enabled mount refuses to start unless at
     * least one {@link HttpConfig} idle/read/write timeout is armed, since MCP relies entirely on
     * that shared bound to ever reclaim a hanging interceptor, tool handler, or non-reading client.
     */
    @Nested
    @DisplayName("HttpConfig liveness gate")
    class HttpLivenessGate {

        private final McpToolRegistry emptyRegistry = McpToolRegistry.build(Set.of());

        @Test
        @DisplayName("rejects an enabled mount when every HttpConfig liveness timeout is zero (the default)")
        void shouldRejectEnabledMountWithNoLivenessTimeoutArmed() {
            assertThatThrownBy(() -> validator.validate(
                            enabled(),
                            Set.of(),
                            emptyRegistry,
                            HttpConfig.builder().build()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("http.idleTimeoutSeconds")
                    .hasMessageContaining("http.readIdleTimeoutSeconds")
                    .hasMessageContaining("http.writeIdleTimeoutSeconds");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.server.McpServerConfigTest#armedLivenessTimeoutRows")
        @DisplayName("accepts an enabled mount when at least one qualifying HttpConfig liveness timeout is armed")
        void shouldAcceptEnabledMountWithAnyLivenessTimeoutArmed(String row, HttpConfig httpConfig) {
            assertThatCode(() -> validator.validate(enabled(), Set.of(), emptyRegistry, httpConfig))
                    .doesNotThrowAnyException();
        }

        /**
         * Repair task R33 defect 4: {@code writeIdleTimeoutSeconds} alone never bounds a client that
         * opens a connection and then neither reads nor writes again — it fires only while a write is
         * actually in flight — so it must not, by itself, satisfy the liveness gate. RED today: the
         * validator's current {@code anyLivenessTimeoutArmed} check ORs in {@code
         * writeIdleTimeoutSeconds() > 0} unconditionally, so this configuration is wrongly accepted.
         */
        @Test
        @DisplayName("rejects an enabled mount when only HttpConfig.writeIdleTimeoutSeconds is armed (R33 defect 4)")
        void shouldRejectEnabledMountWithOnlyWriteIdleTimeoutArmed() {
            assertThatThrownBy(() -> validator.validate(
                            enabled(),
                            Set.of(),
                            emptyRegistry,
                            HttpConfig.builder().writeIdleTimeoutSeconds(30).build()))
                    .as("RED today (R33 defect 4): a write-idle timeout alone must not satisfy the MCP "
                            + "liveness gate")
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("http.idleTimeoutSeconds")
                    .hasMessageContaining("http.readIdleTimeoutSeconds")
                    .hasMessageContaining("http.writeIdleTimeoutSeconds");
        }

        @Test
        @DisplayName("never applies the gate to a disabled mount, even with every timeout zero")
        void shouldNotGateADisabledMount() {
            McpServerConfig disabled = McpServerConfig.defaults();
            assertThatCode(() -> validator.validate(
                            disabled,
                            Set.of(),
                            emptyRegistry,
                            HttpConfig.builder().build()))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The two liveness timeouts that qualify a mount on their own (R33 defect 4 removed {@code
     * writeIdleTimeoutSeconds} from this set — see {@code
     * shouldRejectEnabledMountWithOnlyWriteIdleTimeoutArmed}).
     */
    private static Stream<Arguments> armedLivenessTimeoutRows() {
        return Stream.of(
                Arguments.of(
                        "idleTimeoutSeconds only",
                        HttpConfig.builder().idleTimeoutSeconds(30).build()),
                Arguments.of(
                        "readIdleTimeoutSeconds only",
                        HttpConfig.builder().readIdleTimeoutSeconds(30).build()));
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

    /**
     * The contract's bounded numeric properties, with the exact ranges the validator enforces.
     *
     * <p>Repair task R33 defect 3: the three key literals below are the <em>corrected</em> emitted
     * key names ({@code mcp.outputMaxBytes}, {@code mcp.toolsPageSize}, {@code mcp.toolsTtlMs}) — RED
     * today, since {@link McpServerConfigValidator} still emits the stale dotted forms ({@code
     * mcp.output.maxBytes}, {@code mcp.tools.pageSize}, {@code mcp.tools.ttlMs}), which do not
     * substring-match these corrected literals in {@code shouldRejectValuePastBound}'s {@code
     * hasMessageContaining(key)} assertion.
     */
    private static Stream<NumericProperty> numericProperties() {
        return Stream.of(
                new NumericProperty(
                        "mcp.outputMaxBytes",
                        1_024,
                        16_777_216,
                        (builder, value) -> builder.outputMaxBytes(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.toolsPageSize", 1, 500, (builder, value) -> builder.toolsPageSize(Math.toIntExact(value))),
                new NumericProperty(
                        "mcp.toolsTtlMs", 0, 3_600_000, McpServerConfig.McpServerConfigBuilder::toolsTtlMs));
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

    /** The six implementation-era spellings R43 stopped rejecting; see {@link UnknownAndFormerlyRetiredKeys}. */
    private static Stream<String> formerlyRetiredKeys() {
        return Stream.of(
                "requestTimeoutMs",
                "jsonMaxDepth",
                "jsonMaxPropertiesPerObject",
                "jsonMaxItemsPerArray",
                "jsonMaxStringChars",
                "toolsListDeadlineMs");
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

    private void assertTokenBudgetIsRejected(ConfigParser parser, String property, int value, String flatKey) {
        McpServerConfig configuration = parser.parse(new JsonObject().put(property, value), McpServerConfig.class);

        assertThatThrownBy(() -> validator.validate(configuration))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid configuration: " + flatKey + " must be between 1024 and 262144 inclusive");
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

    /**
     * R43 framework wiring. Builds a raw JSON {@code mcp} section carrying unrecognized property
     * names — including the six formerly retired spellings (issue #424, R02 TP-002) — alongside a
     * valid enabled configuration and the retained bounded properties, and drives every parse
     * through the real {@code dev.vertique.config.parser.DefaultConfigParser} over the real lenient
     * {@code dev.vertique.config.parser.DefaultConfigMapper} — the production configuration mapper —
     * rather than a bespoke test-local {@code ObjectMapper}.
     */
    private static final class McpServerConfigUnknownKeyTestFixture {

        static final int RETAINED_OUTPUT_MAX_BYTES = 4_096;
        static final int RETAINED_TOOLS_PAGE_SIZE = 50;

        private McpServerConfigUnknownKeyTestFixture() {}

        /**
         * Creates a lenient {@link ConfigParser} instance for test-side config parsing — the same
         * production class every other Dagger boundary provider in this framework injects (see
         * {@code docs/standards/config.md}).
         *
         * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
         */
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }

        /**
         * Parses an {@code mcp} section carrying a valid enabled configuration, the retained bounded
         * properties, and every one of {@code unknownKeys} (each set to an arbitrary value), through
         * the production {@link #configParser()}.
         *
         * @param unknownKeys the unrecognized property names to include in the parsed section
         * @return the parsed configuration
         */
        static McpServerConfig parse(Set<String> unknownKeys) {
            JsonObject section = baseSection();
            unknownKeys.forEach(key -> section.put(key, 999));
            return configParser().parse(section, McpServerConfig.class);
        }

        /**
         * Parses an {@code mcp} section carrying a valid enabled configuration, the retained bounded
         * properties, and one ordinary unrecognized key — proving ordinary forward compatibility.
         *
         * @return the parsed configuration
         */
        static McpServerConfig parseWithOrdinaryUnknownKey() {
            JsonObject section = baseSection();
            section.put("someFutureUnrecognizedFeatureFlag", true);
            return configParser().parse(section, McpServerConfig.class);
        }

        private static JsonObject baseSection() {
            return new JsonObject()
                    .put("enabled", true)
                    .put("serverName", "server")
                    .put("serverVersion", "1.0")
                    .put("outputMaxBytes", RETAINED_OUTPUT_MAX_BYTES)
                    .put("toolsPageSize", RETAINED_TOOLS_PAGE_SIZE);
        }
    }
}
