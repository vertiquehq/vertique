// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Convenience factory for creating {@link JWTAuth} instances from common key sources.
 *
 * <p>Simplifies JWT authentication setup by providing one-liner creation methods:
 * <pre>{@code
 * // From a JWKS file on the classpath
 * JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json");
 *
 * // From a remote JWKS endpoint
 * JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json");
 *
 * // From a JWKS endpoint with issuer/audience validation
 * JwtValidationConfig config = JwtValidationConfig.builder()
 *     .issuer("https://auth.example.com/")
 *     .audience(List.of("https://api.example.com"))
 *     .build();
 * JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json", config);
 *
 * // From a filesystem path
 * JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "/etc/secrets/jwks.json");
 *
 * // From a symmetric key (HS256, HS384, HS512)
 * JWTAuth auth = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", "my-secret-key");
 *
 * // From a PEM-encoded public key (RS256, ES256, etc.)
 * JWTAuth auth = JwtAuthFactory.fromPublicKey(vertx, "RS256", pemString);
 *
 * // Any key source with issuer/audience validation
 * JWTAuth auth = JwtAuthFactory.fromPublicKey(vertx, "RS256", pemString, config);
 * }</pre>
 *
 * <p>Every method applies a {@link JwtValidationConfig} to the {@link JWTAuth} it builds. The
 * overloads that take no config apply {@code JwtValidationConfig.builder().build()}, so the
 * documented default clock skew ({@link JwtValidationConfig#clockSkewSeconds()}) always reaches
 * Vert.x as {@code exp}/{@code nbf}/{@code iat} leeway; they leave issuer and audience
 * unconstrained and log no warning about it, because the caller did not ask for validation.
 *
 * <p>This class is a standalone utility and is not managed by Dagger. Applications call
 * these methods inside their {@code @Provides JWTAuth} binding.
 *
 * @see JwtAuthModule
 * @see JwtValidationConfig
 */
@Slf4j
public final class JwtAuthFactory {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private JwtAuthFactory() {}

    /**
     * Creates a {@link JWTAuth} instance from a JWKS (JSON Web Key Set) document.
     *
     * <p>The location is auto-detected by prefix:
     * <ul>
     *   <li>{@code "classpath:path/to/jwks.json"} — reads from the classpath</li>
     *   <li>{@code "http://"} or {@code "https://"} — fetches via HTTP (synchronous;
     *       suitable for startup-time initialization in a {@code @Provides} method)</li>
     *   <li>Anything else — treats as a filesystem path and reads via
     *       {@code vertx.fileSystem().readFileBlocking()}</li>
     * </ul>
     *
     * <p><strong>Warning:</strong> For {@code http://} and {@code https://} locations, this method
     * performs synchronous I/O that blocks the calling thread. When called from an event-loop
     * thread (e.g., inside {@code Verticle.start()}), use {@link #fromJwksAsync(Vertx, String)}
     * instead and compose the result into application startup — the {@code JWTAuth} must exist
     * before the Dagger component that consumes it is built:
     * <pre>{@code
     * JwtAuthFactory.fromJwksAsync(vertx, jwksUri)
     *     .compose(jwtAuth -> VertiqueApplicationBootstrap.start(
     *             VertiqueRuntime.of(vertx, config()),
     *             rt -> DaggerAppComponent.builder()
     *                     .vertxModule(new VertxModule(rt.vertx(), rt.config()))
     *                     .appModule(new AppModule(jwtAuth))
     *                     .build()));
     * }</pre>
     * <p>That call resolves to a {@code Future<VertiqueApplicationHandle<C>>}; a custom host must
     * retain the handle and delegate shutdown to it — see {@code dev.vertique:vertique-application}
     * for the full startup/shutdown contract.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}, 30 seconds) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromJwks(Vertx, String, JwtValidationConfig)} to constrain them.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @return a configured JWTAuth instance
     * @throws IllegalArgumentException if location is null/blank or the document has no "keys" array
     * @throws UncheckedIOException     if reading or fetching the document fails
     */
    public static JWTAuth fromJwks(Vertx vertx, String location) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");

        String content = readLocation(vertx, location);
        return createFromJwksContent(vertx, content);
    }

    /**
     * Asynchronous variant of {@link #fromJwks(Vertx, String)} that returns a {@link Future}.
     *
     * <p>For {@code http://} and {@code https://} locations, runs the HTTP fetch on a
     * Vert.x worker thread via {@code vertx.executeBlocking()}. For classpath and
     * filesystem locations, delegates to the synchronous {@link #fromJwks} wrapped in a
     * succeeded future.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}, 30 seconds) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromJwksAsync(Vertx, String, JwtValidationConfig)} to constrain them.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @return a future that completes with a configured JWTAuth instance
     */
    public static Future<JWTAuth> fromJwksAsync(Vertx vertx, String location) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");

        if (location.startsWith("http://") || location.startsWith("https://")) {
            return vertx.executeBlocking(() -> {
                String content = fetchHttp(location);
                return createFromJwksContent(vertx, content);
            });
        }
        try {
            return Future.succeededFuture(fromJwks(vertx, location));
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Creates a {@link JWTAuth} instance from a JWKS document with JWT validation constraints.
     *
     * <p>Behaves identically to {@link #fromJwks(Vertx, String)} but additionally configures
     * issuer, audience, and clock-skew validation from the supplied {@link JwtValidationConfig}.
     *
     * <p>A startup warning is logged when {@code config.issuer()} or {@code config.audience()}
     * is {@code null}, as this leaves the token open to substitution attacks.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @param config   the validation constraints to apply
     * @return a configured JWTAuth instance
     * @throws IllegalArgumentException if location is null/blank or the document has no "keys" array
     * @throws UncheckedIOException     if reading or fetching the document fails
     */
    public static JWTAuth fromJwks(Vertx vertx, String location, JwtValidationConfig config) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");
        Objects.requireNonNull(config, "config");

        String content = readLocation(vertx, location);
        return createFromJwksContent(vertx, content, config);
    }

    /**
     * Asynchronous variant of {@link #fromJwks(Vertx, String, JwtValidationConfig)}.
     *
     * <p>For {@code http://} and {@code https://} locations, runs the HTTP fetch on a Vert.x
     * worker thread via {@code vertx.executeBlocking()}. For classpath and filesystem locations,
     * delegates to the synchronous overload wrapped in a succeeded future.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @param config   the validation constraints to apply
     * @return a future that completes with a configured JWTAuth instance
     */
    public static Future<JWTAuth> fromJwksAsync(Vertx vertx, String location, JwtValidationConfig config) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");
        Objects.requireNonNull(config, "config");

        if (location.startsWith("http://") || location.startsWith("https://")) {
            return vertx.executeBlocking(() -> {
                String content = fetchHttp(location);
                return createFromJwksContent(vertx, content, config);
            });
        }
        try {
            return Future.succeededFuture(fromJwks(vertx, location, config));
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Creates a self-refreshing {@link JWTAuth} that periodically re-fetches a JWKS document.
     *
     * <p>Loads the initial key set from the given location using
     * {@link #fromJwksAsync(Vertx, String)}, then registers a Vert.x periodic timer to refresh
     * the keys at the specified interval. The returned {@link JWTAuth} is a
     * {@link RefreshableJwtAuth} that transparently swaps its internal delegate on each
     * successful refresh.
     *
     * <p>Example:
     * <pre>{@code
     * JwtAuthFactory.fromJwksRefreshing(vertx, "https://auth.example.com/.well-known/jwks.json",
     *         Duration.ofMinutes(5))
     *     .compose(jwtAuth -> VertiqueApplicationBootstrap.start(
     *             VertiqueRuntime.of(vertx, config()),
     *             rt -> DaggerAppComponent.builder()
     *                     .vertxModule(new VertxModule(rt.vertx(), rt.config()))
     *                     .appModule(new AppModule(jwtAuth))
     *                     .build()));
     * }</pre>
     * <p>That call resolves to a {@code Future<VertiqueApplicationHandle<C>>}; a custom host must
     * retain the handle and delegate shutdown to it — see {@code dev.vertique:vertique-application}
     * for the full startup/shutdown contract.
     *
     * @param vertx           the Vert.x instance
     * @param location        the JWKS document location (classpath, filesystem, or HTTP URL)
     * @param refreshInterval how often to refresh the JWKS keys
     * @return a future that completes with a self-refreshing JWTAuth instance
     * @see RefreshableJwtAuth
     */
    public static Future<JWTAuth> fromJwksRefreshing(Vertx vertx, String location, Duration refreshInterval) {
        return RefreshableJwtAuth.create(vertx, location, refreshInterval).map(auth -> auth);
    }

    /**
     * Creates a {@link JWTAuth} instance from a symmetric (HMAC) key.
     *
     * <p>Suitable for HS256, HS384, and HS512 algorithms.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}, 30 seconds) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromSymmetricKey(Vertx, String, String, JwtValidationConfig)} to constrain them.
     *
     * @param vertx     the Vert.x instance
     * @param algorithm the HMAC algorithm (e.g., "HS256")
     * @param secret    the symmetric key
     * @return a configured JWTAuth instance
     */
    public static JWTAuth fromSymmetricKey(Vertx vertx, String algorithm, String secret) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(algorithm, "algorithm");
        requireNonBlank(secret, "secret");

        return createFromPubSecKey(vertx, algorithm, secret, defaultValidation(), false);
    }

    /**
     * Creates a {@link JWTAuth} from a symmetric (HMAC) key, applying the supplied validation
     * constraints (issuer, audience, and {@code exp}/{@code nbf}/{@code iat} leeway) as
     * {@link JWTOptions}.
     *
     * <p>A startup warning is logged when {@code config.issuer()} or {@code config.audience()}
     * is {@code null}, as this leaves the token open to substitution attacks.
     *
     * @param vertx     the Vert.x instance
     * @param algorithm the HMAC algorithm (e.g. "HS256")
     * @param secret    the symmetric key
     * @param config    the validation constraints to apply; must not be {@code null}
     * @return a configured JWTAuth instance
     */
    public static JWTAuth fromSymmetricKey(Vertx vertx, String algorithm, String secret, JwtValidationConfig config) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(algorithm, "algorithm");
        requireNonBlank(secret, "secret");
        Objects.requireNonNull(config, "config");

        return createFromPubSecKey(vertx, algorithm, secret, config, true);
    }

    /**
     * Creates a {@link JWTAuth} instance from a PEM-encoded public key.
     *
     * <p>Suitable for asymmetric algorithms such as RS256, RS384, RS512, ES256, ES384,
     * ES512, PS256, PS384, and PS512.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}, 30 seconds) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromPublicKey(Vertx, String, String, JwtValidationConfig)} to constrain them.
     *
     * @param vertx     the Vert.x instance
     * @param algorithm the asymmetric algorithm (e.g., "RS256")
     * @param pem       the PEM-encoded public key
     * @return a configured JWTAuth instance
     */
    public static JWTAuth fromPublicKey(Vertx vertx, String algorithm, String pem) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(algorithm, "algorithm");
        requireNonBlank(pem, "pem");

        return createFromPubSecKey(vertx, algorithm, pem, defaultValidation(), false);
    }

    /**
     * Creates a {@link JWTAuth} from a PEM-encoded public key, applying the supplied validation
     * constraints (issuer, audience, and {@code exp}/{@code nbf}/{@code iat} leeway) as
     * {@link JWTOptions}.
     *
     * <p>A startup warning is logged when {@code config.issuer()} or {@code config.audience()}
     * is {@code null}, as this leaves the token open to substitution attacks.
     *
     * @param vertx     the Vert.x instance
     * @param algorithm the asymmetric algorithm (e.g. "RS256")
     * @param pem       the PEM-encoded public key
     * @param config    the validation constraints to apply; must not be {@code null}
     * @return a configured JWTAuth instance
     */
    public static JWTAuth fromPublicKey(Vertx vertx, String algorithm, String pem, JwtValidationConfig config) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(algorithm, "algorithm");
        requireNonBlank(pem, "pem");
        Objects.requireNonNull(config, "config");

        return createFromPubSecKey(vertx, algorithm, pem, config, true);
    }

    // --- Internal helpers ---

    /**
     * Returns the framework's default validation constraints — no issuer, no audience, and the
     * documented default clock skew. Used by every overload that takes no
     * {@link JwtValidationConfig}, so those paths still reach {@link JWTOptions} with leeway
     * applied rather than silently defaulting to Vert.x's leeway of {@code 0}.
     *
     * @return a fresh default {@link JwtValidationConfig}
     */
    private static JwtValidationConfig defaultValidation() {
        return JwtValidationConfig.builder().build();
    }

    /**
     * Creates a {@link JWTAuth} backed by a single symmetric or asymmetric key, applying
     * {@code config} as {@link JWTOptions}.
     *
     * @param vertx                    the Vert.x instance
     * @param algorithm                the signing algorithm
     * @param key                      the symmetric secret or PEM-encoded key
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @return a configured {@link JWTAuth} instance
     */
    private static JWTAuth createFromPubSecKey(
            Vertx vertx, String algorithm, String key, JwtValidationConfig config, boolean warnOnMissingConstraints) {
        return JWTAuth.create(
                vertx,
                new JWTAuthOptions()
                        .addPubSecKey(
                                new PubSecKeyOptions().setAlgorithm(algorithm).setBuffer(key))
                        .setJWTOptions(buildJwtOptions(config, warnOnMissingConstraints)));
    }

    /**
     * Parses a JWKS document and creates a {@link JWTAuth} instance applying the framework's
     * default validation constraints. No missing issuer/audience warning is logged: the caller
     * did not ask for validation, so only the default leeway is being supplied.
     *
     * @param vertx   the Vert.x instance
     * @param content the raw JWKS JSON string
     * @return a configured {@link JWTAuth} instance
     */
    private static JWTAuth createFromJwksContent(Vertx vertx, String content) {
        return createFromJwksContent(vertx, content, defaultValidation(), false);
    }

    /**
     * Parses a JWKS document and creates a {@link JWTAuth} instance applying caller-supplied
     * token validation constraints, warning when issuer or audience is left unset.
     *
     * @param vertx   the Vert.x instance
     * @param content the raw JWKS JSON string
     * @param config  the validation constraints to apply; must not be {@code null}
     * @return a configured {@link JWTAuth} instance
     */
    private static JWTAuth createFromJwksContent(Vertx vertx, String content, JwtValidationConfig config) {
        return createFromJwksContent(vertx, content, config, true);
    }

    /**
     * Parses a JWKS document and creates a {@link JWTAuth} instance, applying token validation
     * constraints from the supplied {@link JwtValidationConfig}.
     *
     * @param vertx                    the Vert.x instance
     * @param content                  the raw JWKS JSON string
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @return a configured {@link JWTAuth} instance
     */
    private static JWTAuth createFromJwksContent(
            Vertx vertx, String content, JwtValidationConfig config, boolean warnOnMissingConstraints) {
        JsonObject jwksDoc;
        try {
            jwksDoc = new JsonObject(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("JWKS document is not valid JSON", e);
        }

        JsonArray keys;
        try {
            keys = jwksDoc.getJsonArray("keys");
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("JWKS 'keys' field is not an array", e);
        }
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("JWKS document does not contain a 'keys' array");
        }

        JWTAuthOptions options = new JWTAuthOptions();
        for (int i = 0; i < keys.size(); i++) {
            options.addJwk(keys.getJsonObject(i));
        }

        options.setJWTOptions(buildJwtOptions(config, warnOnMissingConstraints));

        return JWTAuth.create(vertx, options);
    }

    /**
     * Builds a {@link JWTOptions} from the given {@link JwtValidationConfig}.
     *
     * <p>The missing issuer/audience warnings are gated on {@code warnOnMissingConstraints} rather
     * than emitted unconditionally: every no-config factory path now routes through this method to
     * pick up the default leeway, and those callers never asked for issuer/audience validation, so
     * warning them would be noise.
     *
     * @param config                   the validation configuration; must not be {@code null}
     * @param warnOnMissingConstraints {@code true} when the config came from a caller — an unset
     *                                 issuer or audience is then worth a startup warning;
     *                                 {@code false} when the framework supplied the defaults
     * @return a configured {@link JWTOptions} instance
     */
    private static JWTOptions buildJwtOptions(JwtValidationConfig config, boolean warnOnMissingConstraints) {
        if (warnOnMissingConstraints && config.issuer() == null) {
            log.warn("JwtValidationConfig: issuer is not set. Tokens from any issuer will be accepted. "
                    + "Set 'issuer' in production to prevent token substitution attacks.");
        }
        if (warnOnMissingConstraints
                && (config.audience() == null || config.audience().isEmpty())) {
            log.warn("JwtValidationConfig: audience is not set. Tokens with any audience will be accepted. "
                    + "Set 'audience' in production to prevent token substitution attacks.");
        }

        JWTOptions jwtOptions = new JWTOptions().setLeeway(config.clockSkewSeconds());
        if (config.issuer() != null) {
            jwtOptions.setIssuer(config.issuer());
        }
        if (config.audience() != null && !config.audience().isEmpty()) {
            jwtOptions.setAudience(config.audience());
        }
        return jwtOptions;
    }

    private static String readLocation(Vertx vertx, String location) {
        if (location.startsWith("classpath:")) {
            return readClasspath(location.substring("classpath:".length()));
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return fetchHttp(location);
        }
        return readFilesystem(vertx, location);
    }

    private static String readClasspath(String resource) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new UncheckedIOException(new IOException("Classpath resource not found: " + resource));
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath resource: " + resource, e);
        }
    }

    private static String readFilesystem(Vertx vertx, String path) {
        try {
            return vertx.fileSystem().readFileBlocking(path).toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("Failed to read file: " + path, e));
        }
    }

    private static String fetchHttp(String uri) {
        if (uri.startsWith("http://")) {
            log.warn(
                    "Fetching JWKS over plain HTTP (no TLS): {}. "
                            + "An on-path attacker could inject malicious keys. Use https:// in production.",
                    uri);
        }
        try {
            // Use HTTP/1.1 explicitly. Java 21's HttpClient defaults to HTTP/2 and will attempt
            // an h2c (HTTP/2 over cleartext) upgrade for http:// URLs. Many JWKS endpoints and
            // test servers (e.g. WireMock/Jetty) do not support h2c and return 400 Bad Request
            // for the upgrade request. JWKS fetching is a low-frequency operation; HTTP/1.1 is
            // universally supported and sufficient.
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " fetching JWKS from: " + uri);
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch JWKS from: " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted fetching JWKS from: " + uri, e));
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
