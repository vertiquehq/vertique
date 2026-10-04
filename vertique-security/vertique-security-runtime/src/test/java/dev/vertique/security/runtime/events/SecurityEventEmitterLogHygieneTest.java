// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.TokenAttributes;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Log-hygiene for {@link SecurityEventEmitter#safe}: observer-failure WARN lines must carry the
 * failure class name only — never {@link Throwable#toString()} / message content that may embed
 * event payloads (JWT claims, introspection fields).
 *
 * <p>Pins the exit criterion for {@code vertique-security-runtime} in ADR-0232 / issue #182.
 */
class SecurityEventEmitterLogHygieneTest {

    /**
     * Disclosed {@code aud} claim value — still embedded by {@code "bad event: " + event} via
     * {@link TokenAttributes#toString()}, so a WARN that logged {@code throwable.toString()} would
     * contain it. Must never appear in the emitter's WARN line.
     */
    private static final String CLAIM_CANARY = "super-secret-aud-claim-value-xyz";

    /**
     * Principal id that appears in compiler {@code CredentialAcceptedEvent#toString()}; proves the
     * WARN path does not embed the throwable message that carries the stringified event.
     */
    private static final String PRINCIPAL_CANARY = "principal-secret-id-xyz";

    private Logger emitterLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureEmitterLogs() {
        emitterLogger = (Logger) LoggerFactory.getLogger(SecurityEventEmitter.class);
        previousLevel = emitterLogger.getLevel();
        emitterLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        emitterLogger.addAppender(appender);
    }

    @AfterEach
    void releaseEmitterLogs() {
        emitterLogger.detachAppender(appender);
        appender.stop();
        emitterLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("sync observer throw with event in message: WARN has failure class only, no event payload")
    void syncThrowWithEventInMessageLogsClassNameOnly() {
        CredentialAcceptedEvent event = acceptedEventWithSecrets();
        String wouldBeMessage = "bad event: " + event;
        assertTrue(
                wouldBeMessage.contains(PRINCIPAL_CANARY),
                "precondition: exception message would embed principal id from event toString");
        assertTrue(
                wouldBeMessage.contains(CLAIM_CANARY),
                "precondition: exception message would embed disclosed aud claim from TokenAttributes");

        SecurityEventObserver thrower = new SecurityEventObserver() {
            @Override
            public Future<Void> onCredentialAccepted(CredentialAcceptedEvent e) {
                throw new IllegalStateException("bad event: " + e);
            }
        };

        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(thrower));
        assertTrue(emitter.emit(event).succeeded());

        assertWarnIsClassNameOnly("threw from");
    }

    @Test
    @DisplayName("async observer failure with event in message: WARN has failure class only, no event payload")
    void asyncFailureWithEventInMessageLogsClassNameOnly() {
        CredentialAcceptedEvent event = acceptedEventWithSecrets();
        SecurityEventObserver failing = new SecurityEventObserver() {
            @Override
            public Future<Void> onCredentialAccepted(CredentialAcceptedEvent e) {
                return Future.failedFuture(new IllegalStateException("bad event: " + e));
            }
        };

        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(failing));
        assertTrue(emitter.emit(event).succeeded());

        assertWarnIsClassNameOnly("failed asynchronously");
    }

    private void assertWarnIsClassNameOnly(String pathMarker) {
        ILoggingEvent warn = soleWarn();
        String msg = warn.getFormattedMessage();
        assertTrue(msg.contains(IllegalStateException.class.getName()), "must log failure class: " + msg);
        assertTrue(msg.contains("onCredentialAccepted"), "must log observer method: " + msg);
        assertTrue(msg.contains(pathMarker), "must identify failure path: " + msg);
        assertFalse(msg.contains(CLAIM_CANARY), "must not leak claim value: " + msg);
        assertFalse(msg.contains(PRINCIPAL_CANARY), "must not leak event toString payload: " + msg);
        assertFalse(msg.contains("bad event:"), "must not log throwable message: " + msg);
        assertNull(warn.getThrowableProxy(), "must not attach throwable proxy");
    }

    private ILoggingEvent soleWarn() {
        List<ILoggingEvent> warns =
                appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warns.size(), "exactly one WARN expected, got: " + appender.list);
        return warns.get(0);
    }

    private static CredentialAcceptedEvent acceptedEventWithSecrets() {
        TokenAttributes tokens = new TokenAttributes(
                Optional.empty(), Optional.of(Map.of("sub", "user-1", "aud", CLAIM_CANARY)), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.of(tokens), Map.of());
        SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, PRINCIPAL_CANARY, Map.of()));
        return new CredentialAcceptedEvent(
                Instant.parse("2026-01-01T10:00:00Z"),
                Mockito.mock(dev.vertique.core.correlation.CorrelationContext.class),
                Optional.empty(),
                auth,
                identity);
    }
}
