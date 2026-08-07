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
 * <p>Every returned {@link JWTAuth} also <em>attests</em> the {@link JwtValidationConfig} it was
 * built with, so {@link JwtAuthModule} can fail startup when the applied clock skew diverges from
 * the effective {@code jwt.validation} configuration. The attestation is internal — the returned
 * type is still a plain {@link JWTAuth} and applications' {@code @Provides} signatures are
 * unaffected.
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
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as
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
        return fromJwks(vertx, location, defaultValidation(), false);
    }

    /**
     * Asynchronous variant of {@link #fromJwks(Vertx, String)} that returns a {@link Future}.
     *
     * <p>Every location kind — classpath, filesystem, and {@code http(s)} — is read on a Vert.x
     * worker thread via {@code vertx.executeBlocking()}, so this method never blocks the calling
     * thread and is safe to call from an event loop. An invalid {@code location} still fails fast
     * with {@link IllegalArgumentException} rather than a failed future; a read failure surfaces as
     * a failed future.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromJwksAsync(Vertx, String, JwtValidationConfig)} to constrain them.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @return a future that completes with a configured JWTAuth instance
     */
    public static Future<JWTAuth> fromJwksAsync(Vertx vertx, String location) {
        return fromJwksAsync(vertx, location, defaultValidation(), false);
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
        return fromJwks(vertx, location, config, true);
    }

    /**
     * Shared implementation behind every synchronous {@code fromJwks} overload, with explicit
     * control over the missing issuer/audience advisory.
     *
     * <p>{@code warnOnMissingConstraints} is the only thing its callers vary: the overload that
     * takes a caller-supplied config passes {@code true}, because an unset issuer or audience is
     * then worth a startup warning; the overload that defaults the config passes {@code false},
     * because that caller never asked for issuer/audience validation.
     *
     * @param vertx                    the Vert.x instance
     * @param location                 the JWKS document location
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @return a configured JWTAuth instance
     * @throws IllegalArgumentException if location is null/blank or the document has no "keys" array
     * @throws UncheckedIOException     if reading or fetching the document fails
     */
    private static JWTAuth fromJwks(
            Vertx vertx, String location, JwtValidationConfig config, boolean warnOnMissingConstraints) {
        return fromJwks(vertx, location, config, warnOnMissingConstraints, callerClassLoader());
    }

    /**
     * As {@link #fromJwks(Vertx, String, JwtValidationConfig, boolean)}, resolving a
     * {@code classpath:} location through an explicitly supplied loader instead of the running
     * thread's context classloader.
     *
     * <p>This is the single build pipeline behind both the synchronous and the asynchronous
     * {@code fromJwks} overloads — {@link #fromJwksAsync(Vertx, String, JwtValidationConfig, boolean)}
     * calls it from its {@code executeBlocking} lambda rather than repeating the read-then-create
     * steps. The classloader is a parameter precisely because the two paths run it on different
     * threads: see {@link #callerClassLoader()}.
     *
     * @param vertx                    the Vert.x instance
     * @param location                 the JWKS document location
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @param classpathLoader          the loader used to resolve a {@code classpath:} location; must
     *                                 not be {@code null}
     * @return a configured JWTAuth instance
     * @throws IllegalArgumentException if location is null/blank or the document has no "keys" array
     * @throws UncheckedIOException     if reading or fetching the document fails
     */
    private static JWTAuth fromJwks(
            Vertx vertx,
            String location,
            JwtValidationConfig config,
            boolean warnOnMissingConstraints,
            ClassLoader classpathLoader) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");
        Objects.requireNonNull(config, "config");

        String content = readLocation(vertx, location, classpathLoader);
        return createFromJwksContent(vertx, content, config, warnOnMissingConstraints);
    }

    /**
     * Asynchronous variant of {@link #fromJwks(Vertx, String, JwtValidationConfig)}.
     *
     * <p>Every location kind — classpath, filesystem, and {@code http(s)} — is read on a Vert.x
     * worker thread via {@code vertx.executeBlocking()}, so this method never blocks the calling
     * thread and is safe to call from an event loop.
     *
     * @param vertx    the Vert.x instance
     * @param location the JWKS document location
     * @param config   the validation constraints to apply
     * @return a future that completes with a configured JWTAuth instance
     * @throws IllegalArgumentException if location is null/blank
     */
    public static Future<JWTAuth> fromJwksAsync(Vertx vertx, String location, JwtValidationConfig config) {
        return fromJwksAsync(vertx, location, config, true);
    }

    /**
     * As {@link #fromJwksAsync(Vertx, String, JwtValidationConfig)}, with explicit control over the
     * missing issuer/audience advisory.
     *
     * <p>Package-private seam for framework callers that re-fetch the same location repeatedly —
     * see {@link RefreshableJwtAuth}. The advisory is a startup concern, so a refresh tick passes
     * {@code false} and the warnings are emitted at most once, on the initial load, rather than on
     * every tick for the lifetime of the process.
     *
     * <p>The {@code location}/{@code config} pre-checks run before the dispatch, so an invalid
     * argument still fails fast on the calling thread rather than inside a failed future.
     *
     * @param vertx                    the Vert.x instance
     * @param location                 the JWKS document location
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @return a future that completes with a configured JWTAuth instance
     */
    static Future<JWTAuth> fromJwksAsync(
            Vertx vertx, String location, JwtValidationConfig config, boolean warnOnMissingConstraints) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(location, "location");
        Objects.requireNonNull(config, "config");

        // Captured here, on the caller's thread, and passed into the worker: a classpath: location
        // must resolve against the loader the caller sees. The worker thread comes from the Vert.x
        // pool and its context classloader is not guaranteed to be the caller's, so reading it
        // inside the lambda would make classpath resolution depend on which thread ran the fetch.
        ClassLoader classpathLoader = callerClassLoader();

        // Every location kind reads blocking: classpath and filesystem locations go through
        // vertx.fileSystem().readFileBlocking() / InputStream.readAllBytes() just as an http(s)
        // location goes through a blocking HTTP exchange. They therefore all dispatch to a worker
        // thread — a refresh tick calls this from the event loop, so completing inline would block
        // it on every tick for the lifetime of the process.
        return vertx.executeBlocking(
                () -> fromJwks(vertx, location, config, warnOnMissingConstraints, classpathLoader));
    }

    /**
     * Creates a self-refreshing {@link JWTAuth} that periodically re-fetches a JWKS document.
     *
     * <p>Loads the initial key set from the given location, then registers a Vert.x periodic timer
     * to refresh the keys at the specified interval. The returned {@link RefreshableJwtAuth}
     * transparently swaps its internal delegate on each successful refresh; the concrete type is
     * declared rather than erased to {@link JWTAuth} so callers can reach
     * {@link RefreshableJwtAuth#close()} and stop the timer at shutdown.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()} to the initial key set and to every
     * refreshed one, so the documented default clock skew
     * ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as {@code exp}/{@code nbf}/
     * {@code iat} leeway. Issuer and audience stay unconstrained; use
     * {@link #fromJwksRefreshing(Vertx, String, Duration, JwtValidationConfig)} to constrain them.
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
    public static Future<RefreshableJwtAuth> fromJwksRefreshing(
            Vertx vertx, String location, Duration refreshInterval) {
        return RefreshableJwtAuth.create(vertx, location, refreshInterval);
    }

    /**
     * As {@link #fromJwksRefreshing(Vertx, String, Duration)}, applying {@code config} to the initial
     * key set and to every key set fetched by a subsequent refresh tick.
     *
     * <p>The config is captured once, so a refresh cannot silently relax the issuer, audience, or
     * {@code exp}/{@code nbf}/{@code iat} leeway that guarded the initial key set.
     *
     * <p>A startup warning is logged when {@code config.issuer()} or {@code config.audience()}
     * is {@code null}, as this leaves the token open to substitution attacks.
     *
     * @param vertx           the Vert.x instance
     * @param location        the JWKS document location (classpath, filesystem, or HTTP URL)
     * @param refreshInterval how often to refresh the JWKS keys
     * @param config          the validation constraints to apply; must not be {@code null}
     * @return a future that completes with a self-refreshing JWTAuth instance
     * @see RefreshableJwtAuth
     */
    public static Future<RefreshableJwtAuth> fromJwksRefreshing(
            Vertx vertx, String location, Duration refreshInterval, JwtValidationConfig config) {
        return RefreshableJwtAuth.create(vertx, location, refreshInterval, config);
    }

    /**
     * Creates a {@link JWTAuth} instance from a symmetric (HMAC) key.
     *
     * <p>Suitable for HS256, HS384, and HS512 algorithms.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as
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
     * @return a configured JWTAuth instance that attests {@code config}
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
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as
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
     * <p>Package-private rather than private so
     * {@link RefreshableJwtAuth#create(Vertx, String, Duration)} shares the same authority: one
     * place decides what "the caller supplied no config" means, so the refreshing path cannot
     * drift from the factory paths.
     *
     * @return a fresh default {@link JwtValidationConfig}
     */
    static JwtValidationConfig defaultValidation() {
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
     * @return a configured {@link JWTAuth} instance attesting {@code config}
     */
    private static JWTAuth createFromPubSecKey(
            Vertx vertx, String algorithm, String key, JwtValidationConfig config, boolean warnOnMissingConstraints) {
        return new AttestedJwtAuth(
                JWTAuth.create(
                        vertx,
                        new JWTAuthOptions()
                                .addPubSecKey(new PubSecKeyOptions()
                                        .setAlgorithm(algorithm)
                                        .setBuffer(key))
                                .setJWTOptions(buildJwtOptions(config, warnOnMissingConstraints))),
                config);
    }

    /**
     * Parses a JWKS document and creates a {@link JWTAuth} instance, applying token validation
     * constraints from the supplied {@link JwtValidationConfig}.
     *
     * <p>Callers pass {@code warnOnMissingConstraints = false} together with
     * {@link #defaultValidation()} when the framework — not the application — supplied the config:
     * such a caller never asked for issuer/audience validation, so warning about their absence
     * would be noise. Caller-supplied configs pass {@code true}.
     *
     * @param vertx                    the Vert.x instance
     * @param content                  the raw JWKS JSON string
     * @param config                   the validation constraints to apply; must not be {@code null}
     * @param warnOnMissingConstraints whether to log the missing issuer/audience warnings
     * @return a configured {@link JWTAuth} instance attesting {@code config}
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

        return new AttestedJwtAuth(JWTAuth.create(vertx, options), config);
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
        boolean issuerUnset = config.issuer() == null;
        boolean audienceUnset = config.audience() == null || config.audience().isEmpty();

        if (warnOnMissingConstraints) {
            if (issuerUnset) {
                log.warn("JwtValidationConfig: issuer is not set. Tokens from any issuer will be accepted. "
                        + "Set 'issuer' in production to prevent token substitution attacks.");
            }
            if (audienceUnset) {
                log.warn("JwtValidationConfig: audience is not set. Tokens with any audience will be accepted. "
                        + "Set 'audience' in production to prevent token substitution attacks.");
            }
        }

        JWTOptions jwtOptions = new JWTOptions().setLeeway(config.clockSkewSeconds());
        if (!issuerUnset) {
            jwtOptions.setIssuer(config.issuer());
        }
        if (!audienceUnset) {
            jwtOptions.setAudience(config.audience());
        }
        return jwtOptions;
    }

    /**
     * Returns the classloader a {@code classpath:} location should resolve against, captured from
     * the thread that called into this factory.
     *
     * <p>Callers that hand the read to a worker thread must call this <em>before</em> the dispatch:
     * the worker comes from the Vert.x pool and its context classloader is not guaranteed to be the
     * caller's, so an application running under an isolated or custom classloader would otherwise
     * see classpath resolution succeed or fail depending on which thread performed the read.
     *
     * @return the calling thread's context classloader, or this class's own loader when the thread
     *         has none; never {@code null}
     */
    private static ClassLoader callerClassLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : JwtAuthFactory.class.getClassLoader();
    }

    private static String readLocation(Vertx vertx, String location, ClassLoader classpathLoader) {
        if (location.startsWith("classpath:")) {
            return readClasspath(location.substring("classpath:".length()), classpathLoader);
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return fetchHttp(location);
        }
        return readFilesystem(vertx, location);
    }

    private static String readClasspath(String resource, ClassLoader classpathLoader) {
        try (InputStream is = classpathLoader.getResourceAsStream(resource)) {
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
        // Use HTTP/1.1 explicitly. Java 21's HttpClient defaults to HTTP/2 and will attempt
        // an h2c (HTTP/2 over cleartext) upgrade for http:// URLs. Many JWKS endpoints and
        // test servers (e.g. WireMock/Jetty) do not support h2c and return 400 Bad Request
        // for the upgrade request. JWKS fetching is a low-frequency operation; HTTP/1.1 is
        // universally supported and sufficient.
        //
        // The client is closed per fetch rather than shared: an HttpClient owns a selector thread
        // and an executor, so a refreshing provider that built one per tick would leak both until
        // GC. Reuse across fetches is deliberately not done — a JWKS refresh runs on the order of
        // minutes, so connection setup is not on any hot path.
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build()) {
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
