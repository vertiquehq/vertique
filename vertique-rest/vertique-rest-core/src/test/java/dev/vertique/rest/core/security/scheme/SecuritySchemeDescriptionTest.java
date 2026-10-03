// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves T009's {@code SecuritySchemeDescription} contract (C-SCHEME): the closed, sealed
 * description hierarchy admits exactly the OpenAPI Security Scheme fields, every valid shape
 * builds through its factories with independent copies and unmodifiable maps, every invalid shape
 * is rejected when built, and {@code ApiKey}'s header/cookie names are RFC 9110 tokens.
 *
 * <p>Covers TP-001, TP-002, TP-003, TP-004, and TP-007 of T009.
 */
class SecuritySchemeDescriptionTest {

    // =====================================================================
    // TP-001 — a handler that does not override the method describes nothing
    // =====================================================================

    /**
     * A {@link SecuritySchemeHandler} implementing only {@link #schemeName()} and
     * {@link #configure(SecuritySchemeRegistry)}, as every handler written before this task does.
     */
    private static final class MinimalSecuritySchemeHandler implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            // no-op: this double exists only to exercise the inherited default method.
        }
    }

    @Test
    @DisplayName("A handler that does not override openApiDescription() describes nothing")
    void handlerWithoutOverrideDescribesNothing() {
        SecuritySchemeHandler handler = new MinimalSecuritySchemeHandler();

        assertEquals(Optional.empty(), handler.openApiDescription());
    }

    // =====================================================================
    // TP-002 — the description types admit no field outside the OpenAPI object
    // =====================================================================

    private static final Set<Class<? extends SecuritySchemeDescription>> EXPECTED_PERMITTED_SUBCLASSES =
            Set.of(Http.class, ApiKey.class, OAuth2.class, OpenIdConnect.class, MutualTls.class);

    private static final Map<Class<?>, List<String>> EXPECTED_ACCESSORS = Map.of(
            Http.class,
                    List.of(
                            "bearerFormat(): java.util.Optional<java.lang.String>",
                            "description(): java.util.Optional<java.lang.String>",
                            "scheme(): java.lang.String"),
            ApiKey.class,
                    List.of(
                            "description(): java.util.Optional<java.lang.String>",
                            "in(): dev.vertique.rest.core.security.scheme.ApiKey$Location",
                            "name(): java.lang.String"),
            OAuth2.class,
                    List.of(
                            "description(): java.util.Optional<java.lang.String>",
                            "flows(): dev.vertique.rest.core.security.scheme.OAuthFlows"),
            OpenIdConnect.class,
                    List.of("description(): java.util.Optional<java.lang.String>", "openIdConnectUrl(): java.net.URI"),
            MutualTls.class, List.of("description(): java.util.Optional<java.lang.String>"),
            OAuthFlows.class,
                    List.of(
                            "authorizationCode(): java.util.Optional<dev.vertique.rest.core.security.scheme.OAuthFlow>",
                            "clientCredentials(): java.util.Optional<dev.vertique.rest.core.security.scheme.OAuthFlow>",
                            "implicit(): java.util.Optional<dev.vertique.rest.core.security.scheme.OAuthFlow>",
                            "password(): java.util.Optional<dev.vertique.rest.core.security.scheme.OAuthFlow>"),
            OAuthFlow.class,
                    List.of(
                            "authorizationUrl(): java.util.Optional<java.net.URI>",
                            "refreshUrl(): java.util.Optional<java.net.URI>",
                            "scopes(): java.util.Map<java.lang.String, java.lang.String>",
                            "tokenUrl(): java.util.Optional<java.net.URI>"));

    private static final List<String> EXPECTED_BUILDER_METHODS = List.of(
            "authorizationCode(java.net.URI, java.net.URI, java.util.Map<java.lang.String, java.lang.String>):"
                    + " dev.vertique.rest.core.security.scheme.OAuthFlows$Builder",
            "build(): dev.vertique.rest.core.security.scheme.OAuthFlows",
            "clientCredentials(java.net.URI, java.util.Map<java.lang.String, java.lang.String>):"
                    + " dev.vertique.rest.core.security.scheme.OAuthFlows$Builder",
            "implicit(java.net.URI, java.util.Map<java.lang.String, java.lang.String>):"
                    + " dev.vertique.rest.core.security.scheme.OAuthFlows$Builder",
            "password(java.net.URI, java.util.Map<java.lang.String, java.lang.String>):"
                    + " dev.vertique.rest.core.security.scheme.OAuthFlows$Builder",
            "refreshUrl(java.net.URI): dev.vertique.rest.core.security.scheme.OAuthFlows$Builder");

    private static final Set<String> EXCLUDED_ACCESSOR_NAMES = Set.of("hashCode", "toString");

    /**
     * Renders a class's public, parameterless, non-static, non-synthetic instance methods (other
     * than {@code hashCode} and {@code toString}; {@code equals} is excluded by its own parameter)
     * as sorted {@code "name(): genericReturnType"} strings.
     */
    private static List<String> accessorSignatures(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getParameterCount() == 0)
                .filter(m -> !m.isSynthetic())
                .filter(m -> !EXCLUDED_ACCESSOR_NAMES.contains(m.getName()))
                .map(m -> m.getName() + "(): " + m.getGenericReturnType().getTypeName())
                .sorted()
                .toList();
    }

    /** Renders every public, non-synthetic method (any arity) as a sorted signature string. */
    private static List<String> methodSignatures(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .map(m -> m.getName()
                        + "("
                        + Stream.of(m.getGenericParameterTypes())
                                .map(Type::getTypeName)
                                .collect(Collectors.joining(", "))
                        + "): "
                        + m.getGenericReturnType().getTypeName())
                .sorted()
                .toList();
    }

    private static void assertFinalPrivateConstructorNoPublicField(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " must be final");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            assertTrue(
                    Modifier.isPrivate(constructor.getModifiers()),
                    type.getName() + " must have only private constructors");
        }
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertFalse(
                        Modifier.isPublic(field.getModifiers()),
                        type.getName() + "." + field.getName() + " must not be a public field");
            }
        }
    }

    private static void assertNoForeignOrObjectReturnType(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            Class<?> returnType = m.getReturnType();
            assertNotEquals(Object.class, returnType, type.getSimpleName() + "." + m.getName());
            String pkg = returnType.getPackageName();
            assertFalse(
                    pkg.startsWith("io.swagger") || pkg.startsWith("io.vertx.openapi"),
                    type.getSimpleName() + "." + m.getName() + " returns " + returnType);
        }
    }

    @Test
    @DisplayName("The description types are a closed set of final, factory-built values limited to OpenAPI Security"
            + " Scheme fields")
    void admitsOnlyOpenApiSecuritySchemeFields() {
        assertTrue(SecuritySchemeDescription.class.isSealed(), "SecuritySchemeDescription must be sealed");
        assertEquals(
                EXPECTED_PERMITTED_SUBCLASSES,
                Set.of(SecuritySchemeDescription.class.getPermittedSubclasses()),
                "permitted subclasses");

        List<Class<?>> checkedValueClasses = List.of(
                Http.class,
                ApiKey.class,
                OAuth2.class,
                OpenIdConnect.class,
                MutualTls.class,
                OAuthFlows.class,
                OAuthFlow.class);

        Map<Class<?>, List<String>> reflectedAccessors = new HashMap<>();
        for (Class<?> type : checkedValueClasses) {
            assertFinalPrivateConstructorNoPublicField(type);
            assertNoForeignOrObjectReturnType(type);
            List<String> accessors = accessorSignatures(type);
            assertEquals(EXPECTED_ACCESSORS.get(type), accessors, type.getSimpleName() + " accessors");
            reflectedAccessors.put(type, accessors);
        }

        assertFalse(
                SecuritySchemeDescription.class.isAssignableFrom(OAuthFlows.class),
                "OAuthFlows must not implement SecuritySchemeDescription");
        assertFalse(
                SecuritySchemeDescription.class.isAssignableFrom(OAuthFlow.class),
                "OAuthFlow must not implement SecuritySchemeDescription");

        assertEquals(
                List.of(ApiKey.Location.QUERY, ApiKey.Location.HEADER, ApiKey.Location.COOKIE),
                List.of(ApiKey.Location.values()),
                "ApiKey.Location constants");

        assertEquals(
                EXPECTED_BUILDER_METHODS, methodSignatures(OAuthFlows.Builder.class), "OAuthFlows.Builder methods");

        long mapTypedAccessors = reflectedAccessors.values().stream()
                .flatMap(List::stream)
                .filter(signature -> signature.contains("java.util.Map"))
                .count();
        assertEquals(1, mapTypedAccessors, "exactly one Map-typed accessor across every checked class");
        assertTrue(
                reflectedAccessors
                        .get(OAuthFlow.class)
                        .contains("scopes(): java.util.Map<java.lang.String, java.lang.String>"),
                "the sole Map accessor is OAuthFlow.scopes(): Map<String, String>");
    }

    // =====================================================================
    // TP-003 — every valid description builds, copies are independent, maps unmodifiable
    // =====================================================================

    private record ValidCase(String name, Runnable verification) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validDescriptionCases")
    @DisplayName("Every valid description builds through its factories, copies are independent, and maps are"
            + " unmodifiable")
    void acceptsEveryValidDescription(ValidCase testCase) {
        testCase.verification().run();
    }

    private static Stream<ValidCase> validDescriptionCases() {
        return Stream.of(
                new ValidCase("Http.bearer(\"JWT\")", SecuritySchemeDescriptionTest::verifyHttpBearerJwt),
                new ValidCase("Http.bearer(null)", SecuritySchemeDescriptionTest::verifyHttpBearerNullFormat),
                new ValidCase(
                        "Http.of(\"basic\").withDescription(\"Basic credentials\")",
                        SecuritySchemeDescriptionTest::verifyHttpOfBasicWithDescription),
                new ValidCase("ApiKey.header(\"X-API-Key\")", SecuritySchemeDescriptionTest::verifyApiKeyHeader),
                new ValidCase("ApiKey.query(\"api_key\")", SecuritySchemeDescriptionTest::verifyApiKeyQuery),
                new ValidCase("ApiKey.cookie(\"session\")", SecuritySchemeDescriptionTest::verifyApiKeyCookie),
                new ValidCase(
                        "OAuth2 with all four flows, absolute URLs, and a shared refreshUrl",
                        SecuritySchemeDescriptionTest::verifyOAuth2FourFlowsWithRefreshUrl),
                new ValidCase(
                        "OAuth2 with only clientCredentials and an empty scope map",
                        SecuritySchemeDescriptionTest::verifyOAuth2ClientCredentialsWithEmptyScopeMap),
                new ValidCase(
                        "builder.refreshUrl(...) set before the flow it applies to",
                        SecuritySchemeDescriptionTest::verifyRefreshUrlSetBeforeFlow),
                new ValidCase("OpenIdConnect.of(absolute URL)", SecuritySchemeDescriptionTest::verifyOpenIdConnect),
                new ValidCase("MutualTls.of()", SecuritySchemeDescriptionTest::verifyMutualTls));
    }

    private static void verifyHttpBearerJwt() {
        Http a = Http.bearer("JWT");
        Http b = Http.bearer("JWT");

        assertEquals("bearer", a.scheme());
        assertEquals(Optional.of("JWT"), a.bearerFormat());
        assertEquals(Optional.empty(), a.description());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("JWT"));

        Http described = a.withDescription("Bearer JWT tokens");
        assertEquals(Optional.of("Bearer JWT tokens"), described.description());
        assertEquals("bearer", described.scheme());
        assertEquals(Optional.of("JWT"), described.bearerFormat());
        assertEquals(Optional.empty(), a.description());
    }

    private static void verifyHttpBearerNullFormat() {
        Http a = Http.bearer(null);
        Http b = Http.bearer(null);

        assertEquals("bearer", a.scheme());
        assertEquals(Optional.empty(), a.bearerFormat());
        assertEquals(a, b);
    }

    private static void verifyHttpOfBasicWithDescription() {
        Http base = Http.of("basic");
        assertEquals(Optional.empty(), base.description());

        Http described = base.withDescription("Basic credentials");
        assertEquals("basic", described.scheme());
        assertEquals(Optional.of("Basic credentials"), described.description());
        assertEquals(Optional.empty(), base.description());
        assertEquals(base, Http.of("basic"));
    }

    private static void verifyApiKeyHeader() {
        ApiKey a = ApiKey.header("X-API-Key");
        assertEquals("X-API-Key", a.name());
        assertEquals(ApiKey.Location.HEADER, a.in());
        assertEquals(a, ApiKey.header("X-API-Key"));
        assertEquals(a.hashCode(), ApiKey.header("X-API-Key").hashCode());
        assertTrue(a.toString().contains("X-API-Key"));
        assertTrue(a.toString().contains("HEADER"));

        ApiKey described = a.withDescription("API key header");
        assertEquals("X-API-Key", described.name());
        assertEquals(ApiKey.Location.HEADER, described.in());
        assertEquals(Optional.of("API key header"), described.description());
        assertEquals(Optional.empty(), a.description());
    }

    private static void verifyApiKeyQuery() {
        ApiKey a = ApiKey.query("api_key");
        assertEquals("api_key", a.name());
        assertEquals(ApiKey.Location.QUERY, a.in());
        assertEquals(a, ApiKey.query("api_key"));
        assertEquals(a.hashCode(), ApiKey.query("api_key").hashCode());

        ApiKey described = a.withDescription("API key query parameter");
        assertEquals("api_key", described.name());
        assertEquals(ApiKey.Location.QUERY, described.in());
        assertEquals(Optional.of("API key query parameter"), described.description());
        assertEquals(Optional.empty(), a.description());
    }

    private static void verifyApiKeyCookie() {
        ApiKey a = ApiKey.cookie("session");
        assertEquals("session", a.name());
        assertEquals(ApiKey.Location.COOKIE, a.in());
        assertEquals(a, ApiKey.cookie("session"));
        assertEquals(a.hashCode(), ApiKey.cookie("session").hashCode());

        ApiKey described = a.withDescription("Session cookie");
        assertEquals("session", described.name());
        assertEquals(ApiKey.Location.COOKIE, described.in());
        assertEquals(Optional.of("Session cookie"), described.description());
        assertEquals(Optional.empty(), a.description());
    }

    private static void verifyOAuth2FourFlowsWithRefreshUrl() {
        URI authUrl = URI.create("https://idp.example.com/oauth/authorize");
        URI tokenUrl = URI.create("https://idp.example.com/oauth/token");
        URI refreshUrl = URI.create("https://idp.example.com/oauth/refresh");

        Map<String, String> implicitScopes = new HashMap<>(Map.of("read", "Read access"));
        Map<String, String> passwordScopes = new HashMap<>(Map.of("write", "Write access"));
        Map<String, String> clientCredentialsScopes = new HashMap<>();
        clientCredentialsScopes.put("read:items", "");
        Map<String, String> authorizationCodeScopes = new HashMap<>(Map.of("profile", "Profile access"));

        OAuthFlows flows = OAuthFlows.builder()
                .implicit(authUrl, implicitScopes)
                .password(tokenUrl, passwordScopes)
                .clientCredentials(tokenUrl, clientCredentialsScopes)
                .authorizationCode(authUrl, tokenUrl, authorizationCodeScopes)
                .refreshUrl(refreshUrl)
                .build();
        OAuth2 oauth2 = OAuth2.of(flows);

        assertEquals(Optional.empty(), oauth2.description());
        assertEquals(flows, oauth2.flows());
        assertTrue(oauth2.toString().contains(tokenUrl.toString()));

        OAuthFlow implicitFlow = flows.implicit().orElseThrow();
        assertEquals(Optional.of(authUrl), implicitFlow.authorizationUrl());
        assertEquals(Optional.empty(), implicitFlow.tokenUrl());
        assertEquals(Optional.of(refreshUrl), implicitFlow.refreshUrl());
        assertEquals(Map.of("read", "Read access"), implicitFlow.scopes());

        OAuthFlow passwordFlow = flows.password().orElseThrow();
        assertEquals(Optional.empty(), passwordFlow.authorizationUrl());
        assertEquals(Optional.of(tokenUrl), passwordFlow.tokenUrl());
        assertEquals(Optional.of(refreshUrl), passwordFlow.refreshUrl());

        OAuthFlow clientCredentialsFlow = flows.clientCredentials().orElseThrow();
        assertEquals(Optional.empty(), clientCredentialsFlow.authorizationUrl());
        assertEquals(Optional.of(tokenUrl), clientCredentialsFlow.tokenUrl());
        assertEquals(Optional.of(refreshUrl), clientCredentialsFlow.refreshUrl());
        assertEquals("", clientCredentialsFlow.scopes().get("read:items"));

        OAuthFlow authorizationCodeFlow = flows.authorizationCode().orElseThrow();
        assertEquals(Optional.of(authUrl), authorizationCodeFlow.authorizationUrl());
        assertEquals(Optional.of(tokenUrl), authorizationCodeFlow.tokenUrl());
        assertEquals(Optional.of(refreshUrl), authorizationCodeFlow.refreshUrl());

        // Copy independence: mutating the source map after construction must not reach the flow.
        implicitScopes.put("mutated", "should not appear");
        assertFalse(implicitFlow.scopes().containsKey("mutated"));
        assertThrows(
                UnsupportedOperationException.class, () -> implicitFlow.scopes().put("x", "y"));

        // with* leaves the receiver unchanged and keeps the other field (flows).
        OAuth2 described = oauth2.withDescription("OAuth2 flows");
        assertEquals(Optional.of("OAuth2 flows"), described.description());
        assertEquals(flows, described.flows());
        assertEquals(Optional.empty(), oauth2.description());

        // equals/hashCode across two identically-built values.
        OAuthFlows flows2 = OAuthFlows.builder()
                .implicit(authUrl, Map.of("read", "Read access"))
                .password(tokenUrl, Map.of("write", "Write access"))
                .clientCredentials(tokenUrl, Map.of("read:items", ""))
                .authorizationCode(authUrl, tokenUrl, Map.of("profile", "Profile access"))
                .refreshUrl(refreshUrl)
                .build();
        assertEquals(flows, flows2);
        assertEquals(flows.hashCode(), flows2.hashCode());
        assertEquals(oauth2, OAuth2.of(flows2));
        assertEquals(oauth2.hashCode(), OAuth2.of(flows2).hashCode());
    }

    private static void verifyOAuth2ClientCredentialsWithEmptyScopeMap() {
        URI tokenUrl = URI.create("https://idp.example.com/oauth/token");
        Map<String, String> emptyScopes = new HashMap<>();

        OAuthFlows flows =
                OAuthFlows.builder().clientCredentials(tokenUrl, emptyScopes).build();
        OAuth2 oauth2 = OAuth2.of(flows);

        assertTrue(flows.implicit().isEmpty());
        assertTrue(flows.password().isEmpty());
        assertTrue(flows.authorizationCode().isEmpty());

        OAuthFlow flow = flows.clientCredentials().orElseThrow();
        assertEquals(Optional.of(tokenUrl), flow.tokenUrl());
        assertEquals(Optional.empty(), flow.refreshUrl());
        assertEquals(Map.of(), flow.scopes());
        assertThrows(UnsupportedOperationException.class, () -> flow.scopes().put("x", "y"));

        emptyScopes.put("late", "add");
        assertTrue(flow.scopes().isEmpty());

        assertEquals(
                oauth2,
                OAuth2.of(OAuthFlows.builder()
                        .clientCredentials(tokenUrl, Map.of())
                        .build()));
    }

    private static void verifyOpenIdConnect() {
        URI url = URI.create("https://idp.example.com/.well-known/openid-configuration");
        OpenIdConnect a = OpenIdConnect.of(url);

        assertEquals(url, a.openIdConnectUrl());
        assertEquals(Optional.empty(), a.description());
        assertEquals(a, OpenIdConnect.of(url));
        assertEquals(a.hashCode(), OpenIdConnect.of(url).hashCode());
        assertTrue(a.toString().contains(url.toString()));

        OpenIdConnect described = a.withDescription("OIDC discovery document");
        assertEquals(Optional.of("OIDC discovery document"), described.description());
        assertEquals(url, described.openIdConnectUrl());
        assertEquals(Optional.empty(), a.description());
    }

    private static void verifyMutualTls() {
        MutualTls a = MutualTls.of();

        assertEquals(Optional.empty(), a.description());
        assertEquals(a, MutualTls.of());
        assertEquals(a.hashCode(), MutualTls.of().hashCode());

        MutualTls described = a.withDescription("Client certificate required");
        assertEquals(Optional.of("Client certificate required"), described.description());
        assertEquals(Optional.empty(), a.description());
        assertTrue(described.toString().contains("Client certificate required"));
    }

    private static void verifyRefreshUrlSetBeforeFlow() {
        URI tokenUrl = URI.create("https://idp.example.com/oauth/token");
        URI refreshUrl = URI.create("https://idp.example.com/oauth/refresh");

        OAuthFlows flows = OAuthFlows.builder()
                .refreshUrl(refreshUrl)
                .clientCredentials(tokenUrl, Map.of())
                .build();

        OAuthFlow flow = flows.clientCredentials().orElseThrow();
        assertEquals(Optional.of(refreshUrl), flow.refreshUrl());
    }

    // =====================================================================
    // TP-004 — invalid descriptions are rejected when built
    // =====================================================================

    private record InvalidRow(
            String label,
            Executable call,
            Class<? extends Throwable> expectedType,
            String argumentName,
            boolean skipExactNullMessage) {

        /**
         * Rows whose {@code NullPointerException} message follows the {@code "<argument> must not be
         * null"} convention (every explicit {@code Objects.requireNonNull} check in this package).
         */
        InvalidRow(String label, Executable call, Class<? extends Throwable> expectedType, String argumentName) {
            this(label, call, expectedType, argumentName, false);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final URI ABS_TOKEN_URI = URI.create("https://idp.example.com/oauth/token");
    private static final URI ABS_AUTH_URI = URI.create("https://idp.example.com/oauth/authorize");
    private static final URI REL_URI = URI.create("/oauth/token");
    private static final Map<String, String> VALID_SCOPES = Map.of("read", "Read access");

    private static Http validHttp() {
        return Http.bearer("JWT");
    }

    private static ApiKey validApiKey() {
        return ApiKey.header("X-API-Key");
    }

    private static OAuth2 validOAuth2() {
        return OAuth2.of(OAuthFlows.builder()
                .clientCredentials(ABS_TOKEN_URI, VALID_SCOPES)
                .build());
    }

    private static OpenIdConnect validOpenIdConnect() {
        return OpenIdConnect.of(ABS_TOKEN_URI);
    }

    private static MutualTls validMutualTls() {
        return MutualTls.of();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDescriptionRows")
    @DisplayName("Invalid descriptions are rejected from the call itself, naming the offending argument")
    void rejectsInvalidDescriptions(InvalidRow row) {
        Throwable thrown = assertThrows(row.expectedType(), row.call());
        if (row.expectedType() == NullPointerException.class && !row.skipExactNullMessage()) {
            // An exact match, not a substring, so that removing the explicit null check can no longer
            // pass on the JDK's own helpful NullPointerException text, which also names the argument.
            assertEquals(row.argumentName() + " must not be null", thrown.getMessage(), row.label());
        } else if (row.argumentName() != null) {
            assertNotNull(thrown.getMessage(), row.label());
            assertTrue(
                    thrown.getMessage().contains(row.argumentName()),
                    () -> row.label() + ": expected message to name '" + row.argumentName() + "' but was: "
                            + thrown.getMessage());
        }
    }

    private static Stream<InvalidRow> invalidDescriptionRows() {
        List<InvalidRow> rows = new ArrayList<>();

        // --- Null rows -> NullPointerException ---
        rows.add(new InvalidRow(
                "Http.of(null) throws NPE naming 'scheme'", () -> Http.of(null), NullPointerException.class, "scheme"));
        rows.add(new InvalidRow(
                "Http.bearer(\"JWT\").withDescription(null) throws NPE naming 'description'",
                () -> validHttp().withDescription(null),
                NullPointerException.class,
                "description"));
        rows.add(new InvalidRow(
                "ApiKey.header(\"X-API-Key\").withDescription(null) throws NPE naming 'description'",
                () -> validApiKey().withDescription(null),
                NullPointerException.class,
                "description"));
        rows.add(new InvalidRow(
                "OAuth2.withDescription(null) throws NPE naming 'description'",
                () -> validOAuth2().withDescription(null),
                NullPointerException.class,
                "description"));
        rows.add(new InvalidRow(
                "OpenIdConnect.withDescription(null) throws NPE naming 'description'",
                () -> validOpenIdConnect().withDescription(null),
                NullPointerException.class,
                "description"));
        rows.add(new InvalidRow(
                "MutualTls.withDescription(null) throws NPE naming 'description'",
                () -> validMutualTls().withDescription(null),
                NullPointerException.class,
                "description"));

        rows.add(new InvalidRow(
                "ApiKey.header(null) throws NPE naming 'name'",
                () -> ApiKey.header(null),
                NullPointerException.class,
                "name"));
        rows.add(new InvalidRow(
                "ApiKey.query(null) throws NPE naming 'name'",
                () -> ApiKey.query(null),
                NullPointerException.class,
                "name"));
        rows.add(new InvalidRow(
                "ApiKey.cookie(null) throws NPE naming 'name'",
                () -> ApiKey.cookie(null),
                NullPointerException.class,
                "name"));

        rows.add(new InvalidRow(
                "OAuth2.of(null) throws NPE naming 'flows'",
                () -> OAuth2.of(null),
                NullPointerException.class,
                "flows"));
        rows.add(new InvalidRow(
                "OpenIdConnect.of(null) throws NPE naming 'openIdConnectUrl'",
                () -> OpenIdConnect.of(null),
                NullPointerException.class,
                "openIdConnectUrl"));

        rows.add(new InvalidRow(
                "builder.implicit(null, scopes) throws NPE naming 'authorizationUrl'",
                () -> OAuthFlows.builder().implicit(null, VALID_SCOPES),
                NullPointerException.class,
                "authorizationUrl"));
        rows.add(new InvalidRow(
                "builder.password(null, scopes) throws NPE naming 'tokenUrl'",
                () -> OAuthFlows.builder().password(null, VALID_SCOPES),
                NullPointerException.class,
                "tokenUrl"));
        rows.add(new InvalidRow(
                "builder.clientCredentials(null, scopes) throws NPE naming 'tokenUrl'",
                () -> OAuthFlows.builder().clientCredentials(null, VALID_SCOPES),
                NullPointerException.class,
                "tokenUrl"));
        rows.add(new InvalidRow(
                "builder.authorizationCode(null, tokenUrl, scopes) throws NPE naming 'authorizationUrl'",
                () -> OAuthFlows.builder().authorizationCode(null, ABS_TOKEN_URI, VALID_SCOPES),
                NullPointerException.class,
                "authorizationUrl"));
        rows.add(new InvalidRow(
                "builder.authorizationCode(authUrl, null, scopes) throws NPE naming 'tokenUrl'",
                () -> OAuthFlows.builder().authorizationCode(ABS_AUTH_URI, null, VALID_SCOPES),
                NullPointerException.class,
                "tokenUrl"));

        rows.add(new InvalidRow(
                "builder.implicit(url, null) throws NPE naming 'scopes'",
                () -> OAuthFlows.builder().implicit(ABS_AUTH_URI, null),
                NullPointerException.class,
                "scopes"));
        rows.add(new InvalidRow(
                "builder.password(url, null) throws NPE naming 'scopes'",
                () -> OAuthFlows.builder().password(ABS_TOKEN_URI, null),
                NullPointerException.class,
                "scopes"));
        rows.add(new InvalidRow(
                "builder.clientCredentials(url, null) throws NPE naming 'scopes'",
                () -> OAuthFlows.builder().clientCredentials(ABS_TOKEN_URI, null),
                NullPointerException.class,
                "scopes"));
        rows.add(new InvalidRow(
                "builder.authorizationCode(authUrl, tokenUrl, null) throws NPE naming 'scopes'",
                () -> OAuthFlows.builder().authorizationCode(ABS_AUTH_URI, ABS_TOKEN_URI, null),
                NullPointerException.class,
                "scopes"));

        // These two rows reject a null scope name/description with a message of their own
        // ("scopes must not hold a null scope ..."), not the "<argument> must not be null" convention
        // that Objects.requireNonNull(scopes, ...) uses elsewhere in this class, so they keep the
        // looser substring check instead of the exact-match assertion below.
        rows.add(new InvalidRow(
                "a scope map holding a null key throws NPE naming 'scopes'",
                () -> {
                    Map<String, String> scopes = new HashMap<>();
                    scopes.put(null, "value");
                    OAuthFlows.builder().clientCredentials(ABS_TOKEN_URI, scopes);
                },
                NullPointerException.class,
                "scopes",
                true));
        rows.add(new InvalidRow(
                "a scope map holding a null value throws NPE naming 'scopes'",
                () -> {
                    Map<String, String> scopes = new HashMap<>();
                    scopes.put("read", null);
                    OAuthFlows.builder().clientCredentials(ABS_TOKEN_URI, scopes);
                },
                NullPointerException.class,
                "scopes",
                true));

        rows.add(new InvalidRow(
                "builder.refreshUrl(null) throws NPE naming 'refreshUrl'",
                () -> OAuthFlows.builder().refreshUrl(null),
                NullPointerException.class,
                "refreshUrl"));

        // --- Invalid rows -> IllegalArgumentException ---
        rows.add(new InvalidRow(
                "Http.of(\"\") throws IAE naming 'scheme'",
                () -> Http.of(""),
                IllegalArgumentException.class,
                "scheme"));
        rows.add(new InvalidRow(
                "Http.of(\" \") throws IAE naming 'scheme'",
                () -> Http.of(" "),
                IllegalArgumentException.class,
                "scheme"));
        rows.add(new InvalidRow(
                "Http.bearer(\" \") throws IAE naming 'bearerFormat'",
                () -> Http.bearer(" "),
                IllegalArgumentException.class,
                "bearerFormat"));

        rows.add(new InvalidRow(
                "Http.withDescription(\"\") throws IAE naming 'description'",
                () -> validHttp().withDescription(""),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "ApiKey.withDescription(\"\") throws IAE naming 'description'",
                () -> validApiKey().withDescription(""),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "OAuth2.withDescription(\"\") throws IAE naming 'description'",
                () -> validOAuth2().withDescription(""),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "OpenIdConnect.withDescription(\"\") throws IAE naming 'description'",
                () -> validOpenIdConnect().withDescription(""),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "MutualTls.withDescription(\"\") throws IAE naming 'description'",
                () -> validMutualTls().withDescription(""),
                IllegalArgumentException.class,
                "description"));

        rows.add(new InvalidRow(
                "Http.withDescription(\" \") throws IAE naming 'description'",
                () -> validHttp().withDescription(" "),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "ApiKey.withDescription(\" \") throws IAE naming 'description'",
                () -> validApiKey().withDescription(" "),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "OAuth2.withDescription(\" \") throws IAE naming 'description'",
                () -> validOAuth2().withDescription(" "),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "OpenIdConnect.withDescription(\" \") throws IAE naming 'description'",
                () -> validOpenIdConnect().withDescription(" "),
                IllegalArgumentException.class,
                "description"));
        rows.add(new InvalidRow(
                "MutualTls.withDescription(\" \") throws IAE naming 'description'",
                () -> validMutualTls().withDescription(" "),
                IllegalArgumentException.class,
                "description"));

        rows.add(new InvalidRow(
                "ApiKey.header(\" \") throws IAE naming 'name'",
                () -> ApiKey.header(" "),
                IllegalArgumentException.class,
                "name"));
        rows.add(new InvalidRow(
                "ApiKey.query(\" \") throws IAE naming 'name'",
                () -> ApiKey.query(" "),
                IllegalArgumentException.class,
                "name"));
        rows.add(new InvalidRow(
                "ApiKey.cookie(\" \") throws IAE naming 'name'",
                () -> ApiKey.cookie(" "),
                IllegalArgumentException.class,
                "name"));

        rows.add(new InvalidRow(
                "builder.implicit(relative authorizationUrl, scopes) throws IAE naming 'authorizationUrl'",
                () -> OAuthFlows.builder().implicit(REL_URI, VALID_SCOPES),
                IllegalArgumentException.class,
                "authorizationUrl"));
        rows.add(new InvalidRow(
                "builder.authorizationCode(relative authorizationUrl, tokenUrl, scopes) throws IAE naming"
                        + " 'authorizationUrl'",
                () -> OAuthFlows.builder().authorizationCode(REL_URI, ABS_TOKEN_URI, VALID_SCOPES),
                IllegalArgumentException.class,
                "authorizationUrl"));

        rows.add(new InvalidRow(
                "builder.password(relative tokenUrl, scopes) throws IAE naming 'tokenUrl'",
                () -> OAuthFlows.builder().password(REL_URI, VALID_SCOPES),
                IllegalArgumentException.class,
                "tokenUrl"));
        rows.add(new InvalidRow(
                "builder.clientCredentials(relative tokenUrl, scopes) throws IAE naming 'tokenUrl'",
                () -> OAuthFlows.builder().clientCredentials(REL_URI, VALID_SCOPES),
                IllegalArgumentException.class,
                "tokenUrl"));
        rows.add(new InvalidRow(
                "builder.authorizationCode(authUrl, relative tokenUrl, scopes) throws IAE naming 'tokenUrl'",
                () -> OAuthFlows.builder().authorizationCode(ABS_AUTH_URI, REL_URI, VALID_SCOPES),
                IllegalArgumentException.class,
                "tokenUrl"));

        rows.add(new InvalidRow(
                "builder.refreshUrl(relative URI) throws IAE naming 'refreshUrl'",
                () -> OAuthFlows.builder().implicit(ABS_AUTH_URI, VALID_SCOPES).refreshUrl(REL_URI),
                IllegalArgumentException.class,
                "refreshUrl"));

        rows.add(new InvalidRow(
                "OpenIdConnect.of(relative URI) throws IAE naming 'openIdConnectUrl'",
                () -> OpenIdConnect.of(REL_URI),
                IllegalArgumentException.class,
                "openIdConnectUrl"));

        rows.add(new InvalidRow(
                "a scope map holding an empty scope name throws IAE naming 'scopes'",
                () -> {
                    Map<String, String> scopes = new HashMap<>();
                    scopes.put("", "Read access");
                    OAuthFlows.builder().clientCredentials(ABS_TOKEN_URI, scopes);
                },
                IllegalArgumentException.class,
                "scopes"));
        rows.add(new InvalidRow(
                "a scope map holding a blank scope name throws IAE naming 'scopes'",
                () -> {
                    Map<String, String> scopes = new HashMap<>();
                    scopes.put(" ", "Read access");
                    OAuthFlows.builder().clientCredentials(ABS_TOKEN_URI, scopes);
                },
                IllegalArgumentException.class,
                "scopes"));

        // --- Empty-builder row -> IllegalStateException ---
        rows.add(new InvalidRow(
                "OAuthFlows.builder().build() with no flow throws IllegalStateException",
                OAuthFlows.builder()::build,
                IllegalStateException.class,
                null));

        return rows.stream();
    }

    // =====================================================================
    // TP-007 — header and cookie API key names must be RFC 9110 tokens
    // =====================================================================

    private record TokenRow(
            String label,
            Function<String, ApiKey> factory,
            String name,
            ApiKey.Location expectedLocation,
            boolean valid) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final char TAB = '\t';
    private static final char NUL = '\u0000';
    private static final char DEL = '\u007F';

    /** Every non-alphanumeric {@code tchar}, plus a digit and letters of both cases. */
    private static final String ALL_TCHARS_NAME = "0aZ!#$%&'*+-.^_`|~";

    private static final List<String> VALID_TOKEN_NAMES = List.of("X-API-Key", "api_key", "a", ALL_TCHARS_NAME);

    private static final List<String> INVALID_TOKEN_NAMES = List.of(
            "X API Key",
            "X-Key:1",
            "(key)",
            "key/1",
            "key\"x",
            "a@b",
            "k=v",
            "x;y",
            "x,y",
            "{x}",
            "[x]",
            "?x",
            "x\\y",
            "x" + TAB + "y",
            "\r\nSet-Cookie: y",
            "café",
            String.valueOf(NUL),
            String.valueOf(DEL));

    /** Renders control characters as {@code \\uNNNN} escapes for a readable parameterized test name. */
    private static String describeName(String raw) {
        StringBuilder rendered = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == DEL) {
                rendered.append(String.format("\\u%04x", (int) c));
            } else {
                rendered.append(c);
            }
        }
        return rendered.append('"').toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiKeyNameRows")
    @DisplayName("Header and cookie API key names must be RFC 9110 tokens; query names are exempt")
    void apiKeyHeaderAndCookieNamesMustBeTokens(TokenRow row) {
        if (row.valid()) {
            ApiKey key = row.factory().apply(row.name());
            assertEquals(row.name(), key.name());
            assertEquals(row.expectedLocation(), key.in());

            ApiKey described = key.withDescription("d");
            assertEquals(row.name(), described.name());
        } else {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class, () -> row.factory().apply(row.name()));
            assertNotNull(ex.getMessage(), row.label());
            assertTrue(
                    ex.getMessage().contains("name"),
                    () -> row.label() + ": expected message to name 'name' but was: " + ex.getMessage());
        }
    }

    private static Stream<TokenRow> apiKeyNameRows() {
        List<TokenRow> rows = new ArrayList<>();

        for (String name : VALID_TOKEN_NAMES) {
            rows.add(new TokenRow(
                    "ApiKey.header(" + describeName(name) + ") builds",
                    ApiKey::header,
                    name,
                    ApiKey.Location.HEADER,
                    true));
            rows.add(new TokenRow(
                    "ApiKey.cookie(" + describeName(name) + ") builds",
                    ApiKey::cookie,
                    name,
                    ApiKey.Location.COOKIE,
                    true));
        }

        for (String name : INVALID_TOKEN_NAMES) {
            rows.add(new TokenRow(
                    "ApiKey.header(" + describeName(name) + ") throws IAE naming 'name'",
                    ApiKey::header,
                    name,
                    null,
                    false));
            rows.add(new TokenRow(
                    "ApiKey.cookie(" + describeName(name) + ") throws IAE naming 'name'",
                    ApiKey::cookie,
                    name,
                    null,
                    false));
        }

        rows.add(new TokenRow(
                "ApiKey.query(\"page[size]\") builds despite non-token characters",
                ApiKey::query,
                "page[size]",
                ApiKey.Location.QUERY,
                true));
        rows.add(new TokenRow(
                "ApiKey.query(\"api key\") builds despite the space",
                ApiKey::query,
                "api key",
                ApiKey.Location.QUERY,
                true));

        return rows.stream();
    }
}
