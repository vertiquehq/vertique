// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.ChannelClosedEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SecurityEventObserver} implementation that records security events as Micrometer metrics.
 *
 * <p>Registers the following meters when enabled:
 * <ul>
 *   <li>{@code vertique.security.credentials} — counter with tags {@code outcome} (accepted/rejected)
 *       and {@code method} (the auth method's {@link AuthMethod#normalizedKind()} name)</li>
 *   <li>{@code vertique.security.authz.decisions} — counter with tag {@code decision}
 *       (permit/deny)</li>
 *   <li>{@code vertique.security.channels.active} — gauge tracking open channel count; decrements
 *       are clamped at 0 with a one-time WARN on underflow</li>
 * </ul>
 *
 * <p>Metric recording is a no-op when either the top-level {@code metrics.enabled} flag or the
 * {@code metrics.security.enabled} flag is {@code false} in the configuration. The configuration is
 * cached at construction time for zero-allocation hot-path checks.
 *
 * <p>Every observer method is fully resilient: exceptions from the registry are caught, logged at
 * WARN, swallowed, and a {@link Future#succeededFuture()} is always returned. The emitter isolates
 * observer failures independently, but this observer provides its own guard so that a misconfigured
 * or failing backend cannot surface through the security pipeline.
 *
 * <p>Tag values for auth method use {@link AuthMethod#normalizedKind()} to stay within the bounded
 * {@link dev.vertique.security.AuthMethodKind} set — never the unbounded
 * {@link AuthMethod#id()} — to limit tag cardinality.
 *
 * @see SecurityEventObserver
 * @see MicrometerModule
 */
@Slf4j
@Singleton
final class SecurityMetricsObserver implements SecurityEventObserver {

    // --- Meter names ---

    /** Counter name for accepted and rejected credential events. */
    private static final String CREDENTIALS_COUNTER = "vertique.security.credentials";

    /** Counter name for authorization permit/deny decisions. */
    private static final String AUTHZ_DECISIONS_COUNTER = "vertique.security.authz.decisions";

    /** Gauge name for active persistent channels. */
    private static final String ACTIVE_CHANNELS_GAUGE = "vertique.security.channels.active";

    // --- State ---

    private final MeterRegistry registry;

    /**
     * {@code true} when both top-level metrics and security metrics are enabled. Cached at
     * construction for zero-allocation hot-path checks.
     */
    private final boolean enabled;

    /** Backing integer for the active-channels gauge; registered once at construction. */
    private final AtomicInteger activeChannels = new AtomicInteger(0);

    /**
     * Guards the one-time WARN log on channel gauge underflow. {@code false} = warn not yet emitted.
     */
    private final AtomicBoolean underflowWarnEmitted = new AtomicBoolean(false);

    // --- Constructor ---

    /**
     * Constructs the observer and registers the active-channels gauge if enabled.
     *
     * @param registry the application-wide meter registry
     * @param config   the metrics configuration; controls whether recording is active
     */
    @Inject
    SecurityMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this.registry = registry;
        this.enabled = config.enabled() && config.security().enabled();
        if (enabled) {
            try {
                registry.gauge(ACTIVE_CHANNELS_GAUGE, activeChannels);
            } catch (Exception ex) {
                log.warn(
                        "SecurityMetricsObserver: failed to register active-channels gauge: {}",
                        ex.getClass().getName());
            }
        }
    }

    // --- SecurityEventObserver ---

    /**
     * Records an accepted-credential event as a counter increment with tags
     * {@code outcome=accepted} and {@code method=<normalizedKind>}.
     *
     * @param event the accepted-credential event; never null
     * @return a succeeded {@link Future}; never fails
     */
    @Override
    public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
        if (!enabled) {
            return Future.succeededFuture();
        }
        try {
            String method = resolveMethodTag(event.authentication().primaryMethod());
            credentialsCounter("accepted", method).increment();
        } catch (Exception ex) {
            log.warn(
                    "SecurityMetricsObserver: failed to record onCredentialAccepted metric: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Records a rejected-credential event as a counter increment with tags
     * {@code outcome=rejected} and {@code method=<normalizedKind>}.
     *
     * @param event the rejected-credential event; never null
     * @return a succeeded {@link Future}; never fails
     */
    @Override
    public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
        if (!enabled) {
            return Future.succeededFuture();
        }
        try {
            String method = resolveMethodTag(event.attemptedMethod());
            credentialsCounter("rejected", method).increment();
        } catch (Exception ex) {
            log.warn(
                    "SecurityMetricsObserver: failed to record onCredentialRejected metric: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Records an authorization decision as a counter increment with tag
     * {@code decision=permit} or {@code decision=deny}.
     *
     * @param event the authorization-decision event; never null
     * @return a succeeded {@link Future}; never fails
     */
    @Override
    public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
        if (!enabled) {
            return Future.succeededFuture();
        }
        try {
            String decision = event.decision().permitted() ? "permit" : "deny";
            registry.counter(AUTHZ_DECISIONS_COUNTER, "decision", decision).increment();
        } catch (Exception ex) {
            log.warn(
                    "SecurityMetricsObserver: failed to record onAuthorizationDecided metric: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Updates the active-channels gauge based on the lifecycle event subtype.
     *
     * <ul>
     *   <li>{@link ChannelOpenedEvent} — increments the gauge.</li>
     *   <li>{@link ChannelClosedEvent} — decrements the gauge, clamped at 0; logs a one-time
     *       WARN on underflow.</li>
     *   <li>{@link dev.vertique.security.events.ChannelIdentityRefreshedEvent} — no-op.</li>
     * </ul>
     *
     * @param event the channel-lifecycle event; never null
     * @return a succeeded {@link Future}; never fails
     */
    @Override
    public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
        if (!enabled) {
            return Future.succeededFuture();
        }
        try {
            switch (event) {
                case ChannelOpenedEvent ignored -> activeChannels.incrementAndGet();
                case ChannelClosedEvent ignored -> decrementClamped();
                default -> {
                    // ChannelIdentityRefreshedEvent — no-op
                }
            }
        } catch (Exception ex) {
            log.warn(
                    "SecurityMetricsObserver: failed to record onChannelLifecycle metric: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    // --- Private helpers ---

    /**
     * Builds or retrieves the {@code vertique.security.credentials} counter for the given
     * outcome and method tag combination.
     *
     * @param outcome the outcome tag value ({@code "accepted"} or {@code "rejected"})
     * @param method  the method tag value (normalized kind or {@code "unknown"})
     * @return the counter; never null
     */
    private Counter credentialsCounter(String outcome, String method) {
        return registry.counter(CREDENTIALS_COUNTER, "outcome", outcome, "method", method);
    }

    /**
     * Decrements the active-channels counter, clamping at 0 and emitting a one-time WARN when
     * a decrement would result in a negative value.
     *
     * <p>Uses {@link AtomicInteger#getAndUpdate} to capture the pre-decrement value. If the
     * previous value was 0 the decrement was a clamp (underflow), and the one-time WARN is emitted
     * via an {@link AtomicBoolean} CAS so that only the first underflow logs the message.
     */
    private void decrementClamped() {
        int previous = activeChannels.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (previous == 0 && underflowWarnEmitted.compareAndSet(false, true)) {
            log.warn("SecurityMetricsObserver: active-channel gauge underflow detected — "
                    + "a ChannelClosedEvent was received when the gauge was already 0. "
                    + "This may indicate mismatched open/close events. "
                    + "Further underflows are silently clamped.");
        }
    }

    /**
     * Resolves the metric tag value for an {@link AuthMethod}.
     *
     * <p>Returns the name of {@link AuthMethod#normalizedKind()}, or {@code "unknown"}
     * when the method reference is {@code null} or its kind is {@code null} or blank.
     *
     * @param method the auth method; may be null
     * @return the tag value; never null, never blank
     */
    private static String resolveMethodTag(AuthMethod method) {
        if (method == null) {
            return "unknown";
        }
        AuthMethodKind kind = method.normalizedKind();
        if (kind == null) {
            return "unknown";
        }
        String name = kind.name();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }
}
