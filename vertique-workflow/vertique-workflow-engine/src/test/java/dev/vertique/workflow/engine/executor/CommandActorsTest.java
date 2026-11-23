// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
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
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowAttribution;
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
 * Verifies how the actor of a recorded command is taken from the current security identity: the mapping per kind of
 * identity, that an absent or anonymous identity is decided before the mapper runs, and that logged ids and reasons are
 * stripped of control characters and bounded.
 */
class CommandActorsTest {

    /** Holds the values a test binds; reads only, as the executor's recording path does. */
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
            throw new UnsupportedOperationException("the recording path only reads the context");
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

    private static SecurityIdentity userActingFor(String id, String subject) {
        return new SecurityIdentity(
                new PrincipalRef(PrincipalType.USER, id, Map.of()),
                Optional.of(new PrincipalRef(PrincipalType.USER, subject, Map.of())),
                Optional.empty(),
                Optional.empty());
    }

    private static Held holding(SecurityIdentity identity) {
        return new Held().with(SecurityContext.class, SecurityContexts.unauthenticated(identity));
    }

    private static Stream<Arguments> identities() {
        return Stream.of(
                // label, holder, kind, id, reason, onBehalfOf
                Arguments.of("user u-1", holding(user("u-1")), "USER", "u-1", null, null),
                Arguments.of(
                        "service svc-1 acting for u-7",
                        holding(serviceActingFor("svc-1", "u-7")),
                        "SERVICE",
                        "svc-1",
                        null,
                        "u-7"),
                Arguments.of(
                        "system with reason nightly",
                        holding(SystemIdentities.scheduledJob("nightly")),
                        "SYSTEM",
                        null,
                        "nightly",
                        null),
                Arguments.of(
                        "user whose subject is itself",
                        holding(userActingFor("u-1", "u-1")),
                        "USER",
                        "u-1",
                        null,
                        null),
                Arguments.of("anonymous", holding(SecurityIdentity.anonymous()), "NONE", null, null, null),
                Arguments.of("no security context", new Held(), "NONE", null, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identities")
    @DisplayName("the actor and the party it acts for are taken from the current identity, never from a payload")
    void capturesActorAndOnBehalfOfFromTheCurrentIdentity(
            String label, ContextHolder holder, String kind, String id, String reason, String onBehalfOf) {
        // Given a context holder that holds the identity (or none) and the default mapper
        CommandActors actors = new CommandActors(holder, new DefaultWorkflowActorMapper());

        // When the actor is captured
        var actor = actors.capture();

        // Then the kind, the id, the reason, the party acted for are the expected ones
        WorkflowActor expected =
                switch (kind) {
                    case "USER" -> new WorkflowActor.User(id);
                    case "SERVICE" -> new WorkflowActor.Service(id);
                    case "SYSTEM" -> new WorkflowActor.System(reason);
                    case "NONE" -> null;
                    default -> throw new AssertionError(kind);
                };
        assertThat(actor.actor()).isEqualTo(expected);
        assertThat(actor.onBehalfOf())
                .isEqualTo(onBehalfOf == null ? null : new PrincipalRef(PrincipalType.USER, onBehalfOf, Map.of()));
    }

    @Test
    @DisplayName("a context that carries the snapshot degradation marker is attributed to its actor like any other")
    void aDegradationMarkerDoesNotChangeTheAttribution() {
        // Given a service identity and the degradation marker the security module binds when a snapshot did not verify
        Held holder = holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "relay-svc", Map.of())))
                .with(SnapshotDegradationMarker.class, new SnapshotDegradationMarker("TAMPERED", Optional.empty()));
        CommandActors actors = new CommandActors(holder, new DefaultWorkflowActorMapper());

        // When the actor is captured
        var actor = actors.capture();

        // Then the actor is still the service, with no party acted for, as without the marker
        assertThat(actor.actor()).isEqualTo(new WorkflowActor.Service("relay-svc"));
        assertThat(actor.onBehalfOf()).isNull();
    }

    @Test
    @DisplayName("none is decided before the mapper runs, and a mapper failure refuses the capture unchanged")
    void noneIsDecidedBeforeTheMapperAndAMapperFailureRefuses() {
        // Given a mapper that records every call and throws for the service "bad"
        IllegalStateException denied = new IllegalStateException("denied");
        List<SecurityIdentity> calls = new ArrayList<>();
        WorkflowActorMapper recording = identity -> {
            calls.add(identity);
            if (identity.actor().id().equals("bad")) {
                throw denied;
            }
            return new WorkflowActor.User(identity.actor().id());
        };

        // When the actor is captured with no context and with an anonymous identity
        var noContext = new CommandActors(new Held(), recording).capture();
        var anonymous = new CommandActors(holding(SecurityIdentity.anonymous()), recording).capture();

        // Then both are NONE and the mapper was never called
        assertThat(noContext).isEqualTo(WorkflowAttribution.absent());
        assertThat(anonymous).isEqualTo(WorkflowAttribution.absent());
        assertThat(calls).isEmpty();

        // And with the service "bad" the capture throws the mapper's exception itself
        CommandActors refusing = new CommandActors(
                holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "bad", Map.of()))), recording);
        assertThatThrownBy(refusing::capture).isSameAs(denied);
        assertThat(calls).hasSize(1);
    }

    @Test
    void preservesIdsAndReasonsExactly() {
        String id = "a".repeat(200) + "\n\u2028\u202e";
        var userActor = new CommandActors(holding(user(id)), new DefaultWorkflowActorMapper()).capture();
        assertThat(userActor.actor()).isEqualTo(new WorkflowActor.User(id));

        String reason = "night\tly\u2028run";
        var systemActor = new CommandActors(
                        holding(SystemIdentities.scheduledJob(reason)), new DefaultWorkflowActorMapper())
                .capture();
        assertThat(systemActor.actor()).isEqualTo(new WorkflowActor.System(reason));

        var delegated =
                new CommandActors(holding(serviceActingFor("svc-1", id)), new DefaultWorkflowActorMapper()).capture();
        assertThat(delegated.onBehalfOf()).isEqualTo(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    @Test
    @DisplayName("an id the store cannot hold refuses the capture instead of being cut or cleaned")
    void anUnstorableIdRefusesTheCapture() {
        // Given an identity whose id is over the limit and one whose id holds a NUL character
        CommandActors tooLong = new CommandActors(
                holding(user("a".repeat(WorkflowAttribution.MAX_CODE_POINTS + 1))), new DefaultWorkflowActorMapper());
        CommandActors withNul = new CommandActors(holding(user("u\u0000-1")), new DefaultWorkflowActorMapper());

        // When the actor is captured, then the attribution refuses the text
        assertThatThrownBy(tooLong::capture).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(withNul::capture).isInstanceOf(IllegalArgumentException.class);
    }
}
