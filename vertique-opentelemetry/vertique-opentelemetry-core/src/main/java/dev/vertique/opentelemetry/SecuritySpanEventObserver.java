// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.ChannelClosedEvent;
import dev.vertique.security.events.ChannelIdentityRefreshedEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SecurityEventObserver} implementation that adds span events to the current OpenTelemetry
 * span for security lifecycle events.
 *
 * <p>When enabled, each observer method tests whether a recording span is current (via
 * {@link Span#current()} and {@link Span#isRecording()}) and, if so, adds a named span
 * <em>event</em> to that span (never a new child span). Adding an event rather than a span keeps
 * the operation cheap and avoids polluting the trace tree.
 *
 * <h2>Event names and attributes</h2>
 * <ul>
 *   <li>{@link #onCredentialAccepted} → {@code vertique.security.credential.accepted},
 *       attr {@code vertique.auth.method} = normalized kind (or {@code "unknown"})</li>
 *   <li>{@link #onCredentialRejected} → {@code vertique.security.credential.rejected},
 *       attrs {@code vertique.auth.method} and {@code vertique.auth.reason} (normalized)</li>
 *   <li>{@link #onAuthorizationDecided} → {@code vertique.security.authz.decision},
 *       attrs {@code vertique.authz.decision} ({@code "permit"} / {@code "deny"}) and
 *       {@code vertique.authz.reason} (normalized)</li>
 *   <li>{@link #onChannelLifecycle} →
 *       {@code vertique.security.channel.opened} /
 *       {@code vertique.security.channel.refreshed} /
 *       {@code vertique.security.channel.closed}; NO attributes (channel id excluded)</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * <p>Recording is a no-op when either {@code tracing.enabled=false} or
 * {@code tracing.security.spanEvents=false}. Both flags are cached at construction time for
 * zero-allocation hot-path checks.
 *
 * <h2>Reason code normalization</h2>
 * <p>{@link #normalizeReason(String)} keeps codes that match {@code ^[A-Z0-9_]{1,64}$};
 * null, blank, or non-matching codes are replaced with {@code "OTHER"}.
 *
 * <h2>Never throws</h2>
 * <p>The body of every observer method is wrapped in a broad {@code try/catch}. Any exception from
 * the OpenTelemetry API is caught, logged at WARN with only the exception's class name (no
 * throwable attached — cause-free logging posture), and swallowed. A
 * {@link Future#succeededFuture()} is always returned so the security pipeline is never affected
 * by a tracing failure.
 *
 * @see SecurityEventObserver
 * @see OpenTelemetryModule
 */
@Slf4j
@Singleton
@RegisterIntoSet(SecurityEventObserver.class)
final class SecuritySpanEventObserver implements SecurityEventObserver {

    // --- Event names (frozen per SP-12 plan) ---

    /** Span event name for a successfully accepted credential. */
    private static final String EVENT_CREDENTIAL_ACCEPTED = "vertique.security.credential.accepted";

    /** Span event name for a rejected credential. */
    private static final String EVENT_CREDENTIAL_REJECTED = "vertique.security.credential.rejected";

    /** Span event name for an authorization decision. */
    private static final String EVENT_AUTHZ_DECISION = "vertique.security.authz.decision";

    /** Span event name for a channel-opened lifecycle transition. */
    private static final String EVENT_CHANNEL_OPENED = "vertique.security.channel.opened";

    /** Span event name for a channel-identity-refreshed lifecycle transition. */
    private static final String EVENT_CHANNEL_REFRESHED = "vertique.security.channel.refreshed";

    /** Span event name for a channel-closed lifecycle transition. */
    private static final String EVENT_CHANNEL_CLOSED = "vertique.security.channel.closed";

    // --- Attribute keys ---

    /** Attribute key for the normalized authentication method kind. */
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_AUTH_METHOD =
            io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.method");

    /** Attribute key for the normalized rejection or closure reason code. */
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_AUTH_REASON =
            io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason");

    /** Attribute key for the authorization decision verdict ({@code "permit"} or {@code "deny"}). */
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_AUTHZ_DECISION =
            io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.decision");

    /** Attribute key for the normalized authorization reason code. */
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_AUTHZ_REASON =
            io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.reason");

    /**
     * Compiled pattern matching valid reason codes: 1–64 uppercase letters, digits, or underscores.
     * Pre-compiled to avoid recompilation on every {@link #normalizeReason} call.
     */
    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");

    // --- State ---

    /**
     * {@code true} when both {@code tracing.enabled} and {@code tracing.security.spanEvents} are
     * {@code true}. Cached at construction for zero-allocation hot-path checks.
     */
    private final boolean enabled;

    // --- Constructor ---

    /**
     * Constructs the observer and caches the enabled flag.
     *
     * @param config the tracing configuration; controls whether span events are emitted
     */
    @Inject
    SecuritySpanEventObserver(TracingConfig config) {
        this.enabled = config.enabled() && config.security().spanEvents();
    }

    // --- SecurityEventObserver ---

    /**
     * Adds a {@code vertique.security.credential.accepted} span event to the current recording
     * span, with attribute {@code vertique.auth.method} set to the normalized kind of the
     * primary authentication method.
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
            Span span = Span.current();
            if (span.isRecording()) {
                String method = resolveMethodAttr(event.authentication().primaryMethod());
                span.addEvent(EVENT_CREDENTIAL_ACCEPTED, Attributes.of(ATTR_AUTH_METHOD, method));
            }
        } catch (Exception ex) {
            log.warn(
                    "SecuritySpanEventObserver: failed to add span event for onCredentialAccepted: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Adds a {@code vertique.security.credential.rejected} span event to the current recording
     * span, with attributes {@code vertique.auth.method} and {@code vertique.auth.reason}.
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
            Span span = Span.current();
            if (span.isRecording()) {
                String method = resolveMethodAttr(event.attemptedMethod());
                String reason = normalizeReason(event.reasonCode());
                span.addEvent(
                        EVENT_CREDENTIAL_REJECTED, Attributes.of(ATTR_AUTH_METHOD, method, ATTR_AUTH_REASON, reason));
            }
        } catch (Exception ex) {
            log.warn(
                    "SecuritySpanEventObserver: failed to add span event for onCredentialRejected: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Adds a {@code vertique.security.authz.decision} span event to the current recording span,
     * with attributes {@code vertique.authz.decision} ({@code "permit"} or {@code "deny"}) and
     * {@code vertique.authz.reason} (the normalized reason code from the decision).
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
            Span span = Span.current();
            if (span.isRecording()) {
                String decision = event.decision().permitted() ? "permit" : "deny";
                String reason = normalizeReason(event.decision().reasonCode());
                span.addEvent(
                        EVENT_AUTHZ_DECISION, Attributes.of(ATTR_AUTHZ_DECISION, decision, ATTR_AUTHZ_REASON, reason));
            }
        } catch (Exception ex) {
            log.warn(
                    "SecuritySpanEventObserver: failed to add span event for onAuthorizationDecided: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    /**
     * Adds a channel lifecycle span event ({@code opened} / {@code refreshed} / {@code closed})
     * to the current recording span. No attributes are added — channel identifiers are excluded
     * from spans to limit cardinality and avoid leaking session-tracking data.
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
            Span span = Span.current();
            if (span.isRecording()) {
                String eventName =
                        switch (event) {
                            case ChannelOpenedEvent ignored -> EVENT_CHANNEL_OPENED;
                            case ChannelIdentityRefreshedEvent ignored -> EVENT_CHANNEL_REFRESHED;
                            case ChannelClosedEvent ignored -> EVENT_CHANNEL_CLOSED;
                        };
                span.addEvent(eventName, Attributes.empty());
            }
        } catch (Exception ex) {
            log.warn(
                    "SecuritySpanEventObserver: failed to add span event for onChannelLifecycle: {}",
                    ex.getClass().getName());
        }
        return Future.succeededFuture();
    }

    // --- Private helpers ---

    /**
     * Resolves the {@code vertique.auth.method} attribute value for an {@link AuthMethod}.
     *
     * <p>Returns the name of {@link AuthMethod#normalizedKind()}, or {@code "unknown"} when the
     * method reference is {@code null}, its kind is {@code null}, or the kind name is blank.
     *
     * @param method the auth method; may be null
     * @return the attribute value; never null, never blank
     */
    private static String resolveMethodAttr(AuthMethod method) {
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

    /**
     * Normalizes a reason code for use as a span attribute value.
     *
     * <p>Valid codes match {@code ^[A-Z0-9_]{1,64}$} and are returned as-is. Null, blank, or
     * non-matching codes are replaced with {@code "OTHER"}.
     *
     * @param reasonCode the reason code to normalize; may be null
     * @return the normalized reason code; never null, never blank
     */
    static String normalizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "OTHER";
        }
        return REASON_CODE_PATTERN.matcher(reasonCode).matches() ? reasonCode : "OTHER";
    }
}
