// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SystemIdentities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies how the actor of a workflow action is taken from the current security identity: the mapping per kind of
 * identity, that an anonymous caller is an actor of its own and no security context is no actor, and that the mapper
 * decides who a caller is recorded as.
 */
class CurrentWorkflowActorTest {

    /** Holds the values a test binds; reads only. */
    private static final class Held implements ContextHolder {
        private final Map<Class<?>, Object> values = new HashMap<>();

        Held with(Class<?> type, Object value) {
            values.put(type, value);
            return this;
        }

        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.ofNullable(values.get(type)).map(type::cast);
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            throw new UnsupportedOperationException("the actor is only read from the context");
        }
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static SecurityIdentity serviceActingFor(String service, String subject) {
        return new SecurityIdentity(
                new PrincipalRef(PrincipalType.SERVICE, service, Map.of()),
                Optional.of(new PrincipalRef(PrincipalType.USER, subject, Map.of())),
                Optional.empty(),
                Optional.empty());
    }

    private static Held holding(SecurityIdentity identity) {
        return new Held().with(SecurityContext.class, SecurityContexts.unauthenticated(identity));
    }

    private static Stream<Arguments> identities() {
        return Stream.of(
                Arguments.of("user u-1", holding(user("u-1")), new WorkflowActor.User("u-1")),
                Arguments.of(
                        "service svc-1 acting for a user",
                        holding(serviceActingFor("svc-1", "u-7")),
                        new WorkflowActor.Service("svc-1")),
                Arguments.of(
                        "system with reason nightly",
                        holding(SystemIdentities.scheduledJob("nightly")),
                        new WorkflowActor.System("nightly")),
                Arguments.of("anonymous", holding(SecurityIdentity.anonymous()), new WorkflowActor.Anonymous()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identities")
    @DisplayName("the actor is taken from the current identity through the default mapper")
    void theActorIsTakenFromTheCurrentIdentity(String label, ContextHolder holder, WorkflowActor expected) {
        // Given a holder that holds the identity and the default mapper
        CurrentWorkflowActor current = new CurrentWorkflowActor(holder, new DefaultWorkflowActorMapper());

        // When the current actor is read
        Optional<WorkflowActor> actor = current.get();

        // Then it is the actor the mapper gives for the identity
        assertThat(actor).contains(expected);
    }

    @Test
    @DisplayName("a holder with no security context has no actor, and the mapper is not called")
    void noSecurityContextIsNoActor() {
        // Given a mapper that records every call and a holder with no security context
        List<SecurityIdentity> calls = new ArrayList<>();
        WorkflowActorMapper recording = identity -> {
            calls.add(identity);
            return new WorkflowActor.User(identity.actor().id());
        };

        // When the current actor is read
        Optional<WorkflowActor> actor = new CurrentWorkflowActor(new Held(), recording).get();

        // Then there is none and the mapper never ran
        assertThat(actor).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("the mapper decides who a caller is recorded as, anonymous callers included")
    void theMapperDecidesWhoIsRecorded() {
        // Given a mapper that records a caller acting for a user as that user, and anyone else as the service "other"
        WorkflowActorMapper custom = identity -> identity.subject()
                .<WorkflowActor>map(subject -> new WorkflowActor.User(subject.id()))
                .orElseGet(() -> new WorkflowActor.Service("other"));

        // When a service acting for a user, and an anonymous caller, are read through it
        Optional<WorkflowActor> delegated =
                new CurrentWorkflowActor(holding(serviceActingFor("svc-1", "u-7")), custom).get();
        Optional<WorkflowActor> anonymous =
                new CurrentWorkflowActor(holding(SecurityIdentity.anonymous()), custom).get();

        // Then each is the mapper's result
        assertThat(delegated).contains(new WorkflowActor.User("u-7"));
        assertThat(anonymous).contains(new WorkflowActor.Service("other"));
    }

    @Test
    @DisplayName("an exception the mapper throws propagates unchanged, and a null result is refused")
    void aMapperFailurePropagates() {
        // Given a mapper that throws for the service "bad" and returns null for the service "null"
        IllegalStateException denied = new IllegalStateException("denied");
        WorkflowActorMapper failing = identity -> {
            if (identity.actor().id().equals("bad")) {
                throw denied;
            }
            return null;
        };
        Held bad = holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "bad", Map.of())));
        Held returnsNull = holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "null", Map.of())));

        // When the current actor is read, then the mapper's own exception is thrown
        assertThatThrownBy(() -> new CurrentWorkflowActor(bad, failing).get()).isSameAs(denied);
        // And a null result is a failure, not an absent actor
        assertThatNullPointerException().isThrownBy(() -> new CurrentWorkflowActor(returnsNull, failing).get());
    }

    @Test
    @DisplayName("a context that carries the snapshot degradation marker is mapped like any other")
    void aDegradationMarkerDoesNotChangeTheActor() {
        // Given a service identity and the degradation marker the security module binds when a snapshot did not verify
        Held holder = holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "relay-svc", Map.of())))
                .with(SnapshotDegradationMarker.class, new SnapshotDegradationMarker("TAMPERED", Optional.empty()));

        // When the current actor is read
        Optional<WorkflowActor> actor = new CurrentWorkflowActor(holder, new DefaultWorkflowActorMapper()).get();

        // Then it is still the service
        assertThat(actor).contains(new WorkflowActor.Service("relay-svc"));
    }

    @Test
    @DisplayName("the optional mapper binding resolves to the application's mapper, else the default")
    void theOptionalMapperBindingIsResolved() {
        // Given a holder with a user and an application mapper
        Held holder = holding(user("u-1"));
        WorkflowActorMapper custom = identity -> new WorkflowActor.Service("custom");

        // When the actor is read through the injectable constructor, with and without the binding
        Optional<WorkflowActor> bound = new CurrentWorkflowActor(holder, Optional.of(custom)).get();
        Optional<WorkflowActor> unbound = new CurrentWorkflowActor(holder, Optional.empty()).get();

        // Then the binding wins, and the default maps the user
        assertThat(bound).contains(new WorkflowActor.Service("custom"));
        assertThat(unbound).contains(new WorkflowActor.User("u-1"));
    }

    @Test
    @DisplayName("ids and reasons are preserved exactly, with nothing cut or cleaned")
    void preservesIdsAndReasonsExactly() {
        // Given identities whose text holds control characters and is long
        String id = "a".repeat(300) + "\n ‮";
        String reason = "night\tly run";

        // When they are mapped
        Optional<WorkflowActor> userActor =
                new CurrentWorkflowActor(holding(user(id)), new DefaultWorkflowActorMapper()).get();
        Optional<WorkflowActor> systemActor = new CurrentWorkflowActor(
                        holding(SystemIdentities.scheduledJob(reason)), new DefaultWorkflowActorMapper())
                .get();

        // Then the text is exactly as given
        assertThat(userActor).contains(new WorkflowActor.User(id));
        assertThat(systemActor).contains(new WorkflowActor.System(reason));
    }
}
