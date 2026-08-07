// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link JWTAuth} implementation that wraps a periodically refreshed delegate.
 *
 * <p>This class maintains a {@code volatile} delegate field that is swapped atomically whenever a
 * scheduled JWKS refresh succeeds. In-flight authentication calls that read the delegate before a
 * swap complete normally against the previous key set; calls that begin after the swap use the new
 * key set. There is no locking — reads and writes of a {@code volatile} reference are atomic on the
 * JVM.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Create via {@link #create(Vertx, String, Duration)} — fetches an initial {@link JWTAuth}
 *       and registers a Vert.x periodic timer that fires every {@code refreshInterval}.</li>
 *   <li>Use the returned instance as a regular {@link JWTAuth}.</li>
 *   <li>Call {@link #close()} when the application shuts down to cancel the timer and prevent
 *       further refreshes.</li>
 * </ol>
 *
 * <h2>Timer context</h2>
 * The Vert.x timer fires on the event loop of the {@link Vertx} instance. Each tick dispatches the
 * JWKS fetch to a worker thread via {@link JwtAuthFactory}; the delegate swap happens back on the
 * event loop. Cancelling the timer with {@link #close()} stops future ticks, but an in-progress
 * refresh is allowed to complete harmlessly because {@code closed} is checked before the swap.
 *
 * <p>A refresh tick never logs the missing issuer/audience advisory: that warning belongs to
 * startup, and the validation constraints cannot change after construction, so emitting it on every
 * interval would add nothing but log volume.
 *
 * <h2>Validation constraints</h2>
 * The {@link JwtValidationConfig} supplied at creation is immutable for the instance's lifetime and
 * is re-applied to <em>every</em> delegate, including each one a refresh tick builds. A refreshed
 * key set therefore never silently reverts to a different issuer, audience, or
 * {@code exp}/{@code nbf}/{@code iat} leeway than the initial one.
 *
 * <p>Because that config is stable, this class attests it directly through
 * {@link #appliedValidation()} rather than being wrapped: the value it reports holds for the current
 * delegate and for every future one.
 *
 * <h2>Overlapping refresh prevention</h2>
 * {@link AtomicBoolean} {@code refreshInProgress} guards against concurrent refreshes — if a
 * previous tick's I/O is still in flight when the next tick fires, the new tick returns immediately
 * without starting a second fetch.
 *
 * @see JwtAuthFactory
 */
@Slf4j
public final class RefreshableJwtAuth implements JWTAuth, ValidationAttested {

    // --- State ---

    private final Vertx vertx;
    private final String jwksLocation;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    /**
     * The validation constraints applied to every delegate this instance builds.
     *
     * <p>{@code final} and never re-read from configuration: the constraints that guarded the
     * initial key set must keep guarding every refreshed key set, so {@link #onRefreshTick(long)}
     * passes this same value to each fetch.
     */
    private final JwtValidationConfig validation;

    /**
     * The Vert.x periodic timer ID.
     *
     * <p>Not {@code final} because the timer is started after construction — the instance
     * reference ({@code this}) must exist before {@link Vertx#setPeriodic} is called so that the
     * timer callback can reference {@code this::onRefreshTick}. The field is written exactly once,
     * immediately after the timer is registered, before the instance is published to callers.
     */
    private long timerId = -1;

    /**
     * The current JWT authenticator.
     *
     * <p>Written only from the Vert.x event loop after a successful JWKS refresh, and read from
     * any thread during authentication. {@code volatile} guarantees that reads always see the
     * most-recently written value without requiring explicit locking.
     */
    private volatile JWTAuth delegate;

    // --- Constructor and factory ---

    private RefreshableJwtAuth(Vertx vertx, String jwksLocation, JwtValidationConfig validation, JWTAuth initial) {
        this.vertx = vertx;
        this.jwksLocation = jwksLocation;
        this.validation = validation;
        this.delegate = initial;
    }

    /**
     * Asynchronous factory method that fetches an initial JWKS and starts a background refresh timer.
     *
     * <p>The initial JWKS fetch goes through {@link JwtAuthFactory} and runs on a worker thread for
     * {@code http://} and {@code https://} locations. Once the initial {@link JWTAuth} is ready, a
     * Vert.x periodic timer is registered to refresh the keys every {@code refreshInterval}.
     *
     * <p>Applies {@code JwtValidationConfig.builder().build()}, so the documented default clock
     * skew ({@link JwtValidationConfig#clockSkewSeconds()}) is honored as
     * {@code exp}/{@code nbf}/{@code iat} leeway on the initial key set and on every refreshed one.
     * Issuer and audience stay unconstrained and no warning is logged about it, because the caller
     * did not ask for validation; use
     * {@link #create(Vertx, String, Duration, JwtValidationConfig)} to constrain them.
     *
     * <p>Example usage:
     * <pre>{@code
     * RefreshableJwtAuth.create(vertx, "https://auth.example.com/.well-known/jwks.json", Duration.ofMinutes(5))
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
     * @param vertx           the Vert.x instance, must not be {@code null}
     * @param jwksLocation    the JWKS document location (classpath, filesystem, or HTTP URL),
     *                        must not be {@code null} or blank
     * @param refreshInterval how often to refresh the JWKS keys, must not be {@code null}
     * @return a future that completes with a configured {@link RefreshableJwtAuth} instance
     * @throws IllegalArgumentException if {@code jwksLocation} is blank
     */
    public static Future<RefreshableJwtAuth> create(Vertx vertx, String jwksLocation, Duration refreshInterval) {
        return create(vertx, jwksLocation, refreshInterval, JwtAuthFactory.defaultValidation(), false);
    }

    /**
     * As {@link #create(Vertx, String, Duration)}, applying {@code config} to the initial key set and
     * to every key set fetched by a subsequent refresh tick.
     *
     * <p>The config is captured once and never re-read: a refreshed delegate is built with exactly
     * the constraints the initial one carried, so issuer, audience, and clock-skew leeway cannot
     * silently change underneath a running application when the JWKS endpoint is re-fetched.
     *
     * <p>A warning is logged when {@code config.issuer()} or {@code config.audience()} is
     * {@code null}, as this leaves the token open to substitution attacks. It is logged at most
     * once, on the initial load — refresh ticks never re-emit it.
     *
     * @param vertx           the Vert.x instance, must not be {@code null}
     * @param jwksLocation    the JWKS document location (classpath, filesystem, or HTTP URL),
     *                        must not be {@code null} or blank
     * @param refreshInterval how often to refresh the JWKS keys, must not be {@code null}
     * @param config          the validation constraints to apply; must not be {@code null}
     * @return a future that completes with a configured {@link RefreshableJwtAuth} instance
     * @throws IllegalArgumentException if {@code jwksLocation} is blank
     */
    public static Future<RefreshableJwtAuth> create(
            Vertx vertx, String jwksLocation, Duration refreshInterval, JwtValidationConfig config) {
        return create(vertx, jwksLocation, refreshInterval, config, true);
    }

    /**
     * Shared implementation behind both public {@code create} overloads.
     *
     * <p>{@code warnOnInitialLoad} is the only difference between them: the overload that takes a
     * caller-supplied config passes {@code true}, because an unset issuer or audience is then worth
     * a startup warning; the overload that defaults the config passes {@code false}, because the
     * caller never asked for issuer/audience validation. Either way the flag applies only to the
     * initial load — {@link #onRefreshTick(long)} always suppresses the advisory.
     *
     * @param vertx             the Vert.x instance, must not be {@code null}
     * @param jwksLocation      the JWKS document location, must not be {@code null} or blank
     * @param refreshInterval   how often to refresh the JWKS keys, must not be {@code null}
     * @param config            the validation constraints to apply; must not be {@code null}
     * @param warnOnInitialLoad whether the initial load logs the missing issuer/audience warnings
     * @return a future that completes with a configured {@link RefreshableJwtAuth} instance
     * @throws IllegalArgumentException if {@code jwksLocation} is blank
     */
    private static Future<RefreshableJwtAuth> create(
            Vertx vertx,
            String jwksLocation,
            Duration refreshInterval,
            JwtValidationConfig config,
            boolean warnOnInitialLoad) {
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(jwksLocation, "jwksLocation");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        Objects.requireNonNull(config, "config");

        return JwtAuthFactory.fromJwksAsync(vertx, jwksLocation, config, warnOnInitialLoad)
                .map(initial -> {
                    RefreshableJwtAuth auth = new RefreshableJwtAuth(vertx, jwksLocation, config, initial);
                    auth.timerId = vertx.setPeriodic(refreshInterval.toMillis(), auth::onRefreshTick);
                    return auth;
                });
    }

    /**
     * Stops the periodic refresh timer and marks this instance as closed.
     *
     * <p>After this method returns, no further JWKS refreshes will be attempted. Any in-progress
     * refresh is allowed to finish, but its result is discarded — the delegate is not swapped once
     * {@code closed} is {@code true}.
     *
     * <p>This method is idempotent; calling it more than once has no additional effect.
     */
    public void close() {
        closed.set(true);
        vertx.cancelTimer(timerId);
    }

    // --- ValidationAttested ---

    /**
     * Returns the validation constraints applied to this instance's delegate, including every
     * refreshed delegate. Public by interface rule (implements the package-private
     * {@code ValidationAttested}); the interface type itself is not exported.
     *
     * @return the applied validation config; never {@code null}
     */
    @Override
    public JwtValidationConfig appliedValidation() {
        return validation;
    }

    // --- JWTAuth delegation ---

    /**
     * Authenticates the provided credentials against the current JWKS key set.
     *
     * <p>Delegates to the current {@link #delegate}. If a key refresh is in progress and
     * the delegate is swapped after this call begins, this call completes against the key set
     * that was active when {@code authenticate} was invoked.
     *
     * @param credentials the credentials to authenticate
     * @return a future that completes with the authenticated {@link User}, or fails if
     *         authentication is rejected
     */
    @Override
    public Future<User> authenticate(Credentials credentials) {
        return delegate.authenticate(credentials);
    }

    /**
     * Generates a signed JWT token from the given claims and options.
     *
     * <p>Delegates to the current {@link #delegate}.
     *
     * @param claims  the JWT payload claims as a {@link JsonObject}
     * @param options signing options such as algorithm and expiry
     * @return the signed JWT string
     */
    @Override
    public String generateToken(JsonObject claims, JWTOptions options) {
        return delegate.generateToken(claims, options);
    }

    /**
     * Generates a signed JWT token from the given claims using default options.
     *
     * <p>Delegates to the current {@link #delegate}.
     *
     * @param claims the JWT payload claims as a {@link JsonObject}
     * @return the signed JWT string
     */
    @Override
    public String generateToken(JsonObject claims) {
        return delegate.generateToken(claims);
    }

    // --- Internal helpers ---

    /**
     * Called on each timer tick to refresh the JWKS delegate.
     *
     * <p>Skips the refresh if the instance is closed or if a refresh is already in progress.
     * On success, swaps the delegate only if the instance is still open. Always clears the
     * {@code refreshInProgress} flag on completion, regardless of success or failure.
     *
     * <p>The fetch passes {@link #validation} rather than relying on factory defaults: the swapped-in
     * delegate must enforce the same issuer, audience, and clock-skew leeway the caller configured
     * for the initial key set, otherwise a refresh would silently relax token validation.
     *
     * <p>It passes {@code warnOnMissingConstraints = false} unconditionally. The missing
     * issuer/audience advisory is a startup concern, and {@link #validation} is immutable for this
     * instance's lifetime, so a tick can never surface a constraint gap the initial load did not
     * already report — re-warning every interval would be pure noise for the life of the process.
     *
     * @param id the timer ID provided by Vert.x (unused)
     */
    private void onRefreshTick(long id) {
        if (closed.get() || !refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        JwtAuthFactory.fromJwksAsync(vertx, jwksLocation, validation, false)
                .onSuccess(newAuth -> {
                    if (!closed.get()) {
                        delegate = newAuth;
                        log.info("JWKS refreshed from {}", jwksLocation);
                    }
                })
                .onFailure(err -> log.warn("JWKS refresh failed, keeping existing keys", err))
                .onComplete(v -> refreshInProgress.set(false));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
