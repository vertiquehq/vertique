// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger-wiring tests for {@link SecurityEventsModule}.
 *
 * <p>Verifies that the core module is a self-contained, single canonical declaration of the
 * {@code Set<SecurityEventObserver>} multibinding and that the {@link SecurityEventEmitter} it
 * exposes fans out all four security-event kinds. Because the test component includes only
 * {@link SecurityEventsModule} (no rest-security module), it also proves there is no module
 * cycle and that the multibinding resolves to an empty set when no observer is contributed.
 */
class SecurityEventsModuleTest {

    /**
     * Component including only {@link SecurityEventsModule}. With no observer contribution the
     * {@code Set<SecurityEventObserver>} must resolve to an empty set, proving the {@code @Multibinds}
     * declaration stands alone.
     */
    @Singleton
    @Component(modules = SecurityEventsModule.class)
    interface CoreOnlyComponent {

        /**
         * Returns the canonical observer multibinding set.
         *
         * @return the (empty) observer set; never {@code null}
         */
        Set<SecurityEventObserver> securityEventObservers();

        /**
         * Returns the transport-neutral emitter built on the core multibinding.
         *
         * @return the emitter; never {@code null}
         */
        SecurityEventEmitter securityEventEmitter();
    }

    /**
     * Test module that contributes a single recording {@link SecurityEventObserver} via
     * {@code @IntoSet} so the emitter has exactly one observer to fan out to.
     */
    @Module
    static final class OneObserverModule {

        private final RecordingObserver observer;

        OneObserverModule(RecordingObserver observer) {
            this.observer = observer;
        }

        /**
         * Contributes the recording observer to the canonical multibinding set.
         *
         * @return the recording observer as a {@link SecurityEventObserver}
         */
        @Provides
        @IntoSet
        SecurityEventObserver recordingObserver() {
            return observer;
        }
    }

    /**
     * Component including {@link SecurityEventsModule} plus a single observer contribution.
     */
    @Singleton
    @Component(modules = {SecurityEventsModule.class, OneObserverModule.class})
    interface OneObserverComponent {

        /**
         * Returns the emitter wired to the single-observer set.
         *
         * @return the emitter; never {@code null}
         */
        SecurityEventEmitter securityEventEmitter();
    }

    @Test
    @DisplayName("Set<SecurityEventObserver> is declared once and resolves to an empty set with no contributors")
    void multibinds_setDeclaredOnce() {
        CoreOnlyComponent component = DaggerSecurityEventsModuleTest_CoreOnlyComponent.create();

        Set<SecurityEventObserver> observers = component.securityEventObservers();

        assertNotNull(observers, "observer set must resolve");
        assertTrue(observers.isEmpty(), "no contributors → empty set");
    }

    @Test
    @DisplayName("emitter fans out all four event kinds to the contributed observer")
    void emitter_allFourEventKinds_dispatched() {
        RecordingObserver observer = new RecordingObserver();
        OneObserverComponent component = DaggerSecurityEventsModuleTest_OneObserverComponent.builder()
                .oneObserverModule(new OneObserverModule(observer))
                .build();

        SecurityEventEmitter emitter = component.securityEventEmitter();

        emitter.emit(mock(CredentialAcceptedEvent.class));
        emitter.emit(mock(CredentialRejectedEvent.class));
        emitter.emit(stubAuthzEvent());
        emitter.emit(stubChannelEvent());

        assertEquals(
                EnumSet.allOf(EventKind.class),
                observer.received,
                "observer must receive each of the four event kinds exactly once");
    }

    @Test
    @DisplayName("component including only SecurityEventsModule compiles and resolves (no module cycle)")
    void noModuleCycle() {
        CoreOnlyComponent component = DaggerSecurityEventsModuleTest_CoreOnlyComponent.create();

        assertNotNull(component.securityEventEmitter(), "emitter must resolve from the core module alone");
        assertNotNull(component.securityEventObservers(), "observer set must resolve from the core module alone");
    }

    // --- Fixtures ---

    private static AuthorizationDecisionEvent stubAuthzEvent() {
        ResourceRef resource = new ResourceRef("order", "42", null);
        AuthorizationRequest request = new AuthorizationRequest(mock(SecurityContext.class), "READ", resource, null);
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        return new AuthorizationDecisionEvent(
                Instant.now(), mock(CorrelationContext.class), Optional.empty(), request, decision);
    }

    private static ChannelLifecycleEvent stubChannelEvent() {
        return new ChannelOpenedEvent(
                Instant.now(), "channel-abc", mock(SecurityContext.class), mock(CorrelationContext.class));
    }

    /** The four security-event categories the emitter fans out. */
    private enum EventKind {
        /** {@link CredentialAcceptedEvent}. */
        CREDENTIAL_ACCEPTED,
        /** {@link CredentialRejectedEvent}. */
        CREDENTIAL_REJECTED,
        /** {@link AuthorizationDecisionEvent}. */
        AUTHORIZATION_DECISION,
        /** {@link ChannelLifecycleEvent}. */
        CHANNEL_LIFECYCLE
    }

    /** Recording observer that notes which event kinds it received. */
    static final class RecordingObserver implements SecurityEventObserver {

        private final Set<EventKind> received = EnumSet.noneOf(EventKind.class);

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            received.add(EventKind.CREDENTIAL_ACCEPTED);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            received.add(EventKind.CREDENTIAL_REJECTED);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
            received.add(EventKind.AUTHORIZATION_DECISION);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
            received.add(EventKind.CHANNEL_LIFECYCLE);
            return Future.succeededFuture();
        }
    }
}
