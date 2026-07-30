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
 * JWKS fetch to a worker thread via {@link JwtAuthFactory#fromJwksAsync(Vertx, String)}; the
 * delegate swap happens back on the event loop. Cancelling the timer with {@link #close()} stops
 * future ticks, but an in-progress refresh is allowed to complete harmlessly because {@code closed}
 * is checked before the swap.
 *
 * <h2>Overlapping refresh prevention</h2>
 * {@link AtomicBoolean} {@code refreshInProgress} guards against concurrent refreshes — if a
 * previous tick's I/O is still in flight when the next tick fires, the new tick returns immediately
 * without starting a second fetch.
 *
 * @see JwtAuthFactory#fromJwksAsync(Vertx, String)
 */
@Slf4j
public final class RefreshableJwtAuth implements JWTAuth {

    // --- State ---

    private final Vertx vertx;
    private final String jwksLocation;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

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

    private RefreshableJwtAuth(Vertx vertx, String jwksLocation, JWTAuth initial) {
        this.vertx = vertx;
        this.jwksLocation = jwksLocation;
        this.delegate = initial;
    }

    /**
     * Asynchronous factory method that fetches an initial JWKS and starts a background refresh timer.
     *
     * <p>The initial JWKS fetch uses {@link JwtAuthFactory#fromJwksAsync(Vertx, String)} and runs
     * on a worker thread for {@code http://} and {@code https://} locations. Once the initial
     * {@link JWTAuth} is ready, a Vert.x periodic timer is registered to refresh the keys every
     * {@code refreshInterval}.
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
        Objects.requireNonNull(vertx, "vertx");
        requireNonBlank(jwksLocation, "jwksLocation");
        Objects.requireNonNull(refreshInterval, "refreshInterval");

        return JwtAuthFactory.fromJwksAsync(vertx, jwksLocation).map(initial -> {
            RefreshableJwtAuth auth = new RefreshableJwtAuth(vertx, jwksLocation, initial);
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
     * @param id the timer ID provided by Vert.x (unused)
     */
    private void onRefreshTick(long id) {
        if (closed.get() || !refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        JwtAuthFactory.fromJwksAsync(vertx, jwksLocation)
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
