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
                // label, holder, kind, id, reason, onBehalfOf, attribution
                Arguments.of("user u-1", holding(user("u-1")), "USER", "u-1", null, null, "VERIFIED"),
                Arguments.of(
                        "service svc-1 acting for u-7",
                        holding(serviceActingFor("svc-1", "u-7")),
                        "SERVICE",
                        "svc-1",
                        null,
                        "u-7",
                        "VERIFIED"),
                Arguments.of(
                        "system with reason nightly",
                        holding(SystemIdentities.scheduledJob("nightly")),
                        "SYSTEM",
                        null,
                        "nightly",
                        null,
                        "VERIFIED"),
                Arguments.of(
                        "user whose subject is itself",
                        holding(userActingFor("u-1", "u-1")),
                        "USER",
                        "u-1",
                        null,
                        null,
                        "VERIFIED"),
                Arguments.of("anonymous", holding(SecurityIdentity.anonymous()), "NONE", null, null, null, "ABSENT"),
                Arguments.of("no security context", new Held(), "NONE", null, null, null, "ABSENT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identities")
    @DisplayName("the actor and the party it acts for are taken from the current identity, never from a payload")
    void capturesActorAndOnBehalfOfFromTheCurrentIdentity(
            String label,
            ContextHolder holder,
            String kind,
            String id,
            String reason,
            String onBehalfOf,
            String attribution) {
        // Given a context holder that holds the identity (or none) and the default mapper
        CommandActors actors = new CommandActors(holder, new DefaultWorkflowActorMapper());

        // When the actor is captured
        var actor = actors.capture();

        // Then the kind, the id, the reason, the party acted for and the attribution status are the expected ones
        assertThat(String.valueOf(actor.kind())).isEqualTo(kind);
        if (id != null) {
            assertThat(actor.id()).isEqualTo(id);
        }
        assertThat(actor.reason()).isEqualTo(reason);
        assertThat(actor.onBehalfOf()).isEqualTo(onBehalfOf);
        assertThat(String.valueOf(actor.attribution())).isEqualTo(attribution);
        if (kind.equals("NONE")) {
            assertThat(actor.id()).isNull();
        }
    }

    @Test
    @DisplayName("a context that carries the snapshot degradation marker is attributed as degraded")
    void aDegradationMarkerMakesTheAttributionDegraded() {
        // Given a service identity and the degradation marker the security module binds when a snapshot did not verify
        Held holder = holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "relay-svc", Map.of())))
                .with(SnapshotDegradationMarker.class, new SnapshotDegradationMarker("TAMPERED", Optional.empty()));
        CommandActors actors = new CommandActors(holder, new DefaultWorkflowActorMapper());

        // When the actor is captured
        var actor = actors.capture();

        // Then the actor is still the service, with no party acted for, and the attribution is degraded
        assertThat(String.valueOf(actor.kind())).isEqualTo("SERVICE");
        assertThat(actor.id()).isEqualTo("relay-svc");
        assertThat(actor.onBehalfOf()).isNull();
        assertThat(String.valueOf(actor.attribution())).isEqualTo("DEGRADED");
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
        assertThat(String.valueOf(noContext.kind())).isEqualTo("NONE");
        assertThat(String.valueOf(anonymous.kind())).isEqualTo("NONE");
        assertThat(calls).isEmpty();

        // And with the service "bad" the capture throws the mapper's exception itself
        CommandActors refusing = new CommandActors(
                holding(SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "bad", Map.of()))), recording);
        assertThatThrownBy(refusing::capture).isSameAs(denied);
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("ids and reasons are stripped of control characters and line separators, then cut at 255 characters")
    void idsAndReasonsAreStrippedOfControlCharactersAndTruncated() {
        // Given a user id of 300 characters with control characters and both separators scattered through it
        String id = "a".repeat(50)
                + "\n"
                + "b".repeat(50)
                + "\r"
                + "c".repeat(50)
                + "\u0000"
                + "d".repeat(50)
                + "\u001b"
                + "e".repeat(50)
                + " "
                + "f".repeat(30)
                + " "
                + "g".repeat(14);
        assertThat(id).hasSize(300);
        String cleanId =
                "a".repeat(50) + "b".repeat(50) + "c".repeat(50) + "d".repeat(50) + "e".repeat(50) + "f".repeat(5);

        // When the actor is captured
        var userActor = new CommandActors(holding(user(id)), new DefaultWorkflowActorMapper()).capture();

        // Then no control character and no U+2028 or U+2029 remains, and the id is the first 255 after removal
        assertThat(userActor.id()).hasSize(255).isEqualTo(cleanId);
        assertThat(userActor.id().chars().anyMatch(Character::isISOControl)).isFalse();
        assertThat(userActor.id()).doesNotContain(" ", " ");

        // And a system reason with a tab and a line separator loses both
        var systemActor = new CommandActors(
                        holding(SystemIdentities.scheduledJob("night\tly run")), new DefaultWorkflowActorMapper())
                .capture();
        assertThat(systemActor.reason()).isEqualTo("nightlyrun");

        // And the party acted for is cleaned and bounded in the same way
        String subject = "u-" + " " + "\n" + "x".repeat(300);
        var delegated = new CommandActors(holding(serviceActingFor("svc-1", subject)), new DefaultWorkflowActorMapper())
                .capture();
        assertThat(delegated.onBehalfOf()).hasSize(255).isEqualTo("u-" + "x".repeat(253));
    }
}
