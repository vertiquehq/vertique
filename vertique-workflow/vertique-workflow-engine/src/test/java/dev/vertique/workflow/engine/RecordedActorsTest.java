// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.CurrentWorkflowActor;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies how a recorded command takes its actor from the caller: that a mapper failure refuses the recording before
 * the write runs, and that the executor observers are told about a command recorded with no actor, and only then.
 */
class RecordedActorsTest {

    private static ContextHolder holding(SecurityIdentity identity) {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return identity != null && SecurityContext.class == type
                        ? Optional.of(type.cast(SecurityContexts.unauthenticated(identity)))
                        : Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                throw new UnsupportedOperationException("the recording path only reads the context");
            }
        };
    }

    private static RecordedActors actors(
            ContextHolder holder, WorkflowActorMapper mapper, WorkflowExecutorObserver... observers) {
        return new RecordedActors(new CurrentWorkflowActor(holder, mapper), new LinkedHashSet<>(List.of(observers)));
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    @Test
    @DisplayName("a recording is written with the caller's actor and tells the observers nothing")
    void aRecordingIsWrittenWithTheActor() {
        // Given a caller and an observer
        List<WorkflowExecutorEvent> events = new ArrayList<>();
        RecordedActors actors = actors(holding(user("u-1")), new DefaultWorkflowActorMapper(), events::add);
        List<WorkflowActor> written = new ArrayList<>();

        // When a command is recorded
        Future<Long> result = actors.recording("START", actor -> {
            written.add(actor);
            return Future.succeededFuture(1L);
        });

        // Then the write got the caller's actor, and no actor-absent event was sent
        assertThat(result.succeeded()).isTrue();
        assertThat(written).containsExactly(new WorkflowActor.User("u-1"));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a recording with no security context is written with no actor and reported once")
    void aRecordingWithNoActorIsReported() {
        // Given a holder with no security context and an observer
        List<WorkflowExecutorEvent> events = new ArrayList<>();
        RecordedActors actors = actors(holding(null), new DefaultWorkflowActorMapper(), events::add);
        List<WorkflowActor> written = new ArrayList<>();

        // When a command is recorded
        Future<Long> result = actors.recording("RETRY", actor -> {
            written.add(actor);
            return Future.succeededFuture(2L);
        });

        // Then the write got no actor, and the observer heard of it
        assertThat(result.succeeded()).isTrue();
        assertThat(written).containsExactly((WorkflowActor) null);
        assertThat(events).containsExactly(new WorkflowExecutorEvent.ActorAbsent("RETRY"));
    }

    @Test
    @DisplayName("an anonymous caller is an actor of its own and is not reported as absent")
    void anAnonymousCallerIsNotAbsent() {
        // Given an anonymous caller and an observer
        List<WorkflowExecutorEvent> events = new ArrayList<>();
        RecordedActors actors =
                actors(holding(SecurityIdentity.anonymous()), new DefaultWorkflowActorMapper(), events::add);
        List<WorkflowActor> written = new ArrayList<>();

        // When a command is recorded
        actors.recording("START", actor -> {
            written.add(actor);
            return Future.succeededFuture(3L);
        });

        // Then the write got the anonymous actor and nothing was reported
        assertThat(written).containsExactly(new WorkflowActor.Anonymous());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a mapper failure refuses the recording with the mapper's exception and the write never runs")
    void aMapperFailureRefusesTheRecording() {
        // Given a mapper that throws
        IllegalStateException denied = new IllegalStateException("denied");
        AtomicInteger writes = new AtomicInteger();
        RecordedActors actors = actors(holding(user("u-1")), identity -> {
            throw denied;
        });

        // When a command is recorded
        Future<Long> result = actors.recording("SIGNAL", actor -> {
            writes.incrementAndGet();
            return Future.succeededFuture(4L);
        });

        // Then the recording failed with the mapper's own exception and nothing was written
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(denied);
        assertThat(writes).hasValue(0);
    }

    @Test
    @DisplayName("an observer that throws neither reaches the recording nor stops the other observers")
    void aThrowingObserverIsIsolated() {
        // Given two observers, the first of which throws
        List<WorkflowExecutorEvent> events = new ArrayList<>();
        RecordedActors actors = actors(
                holding(null),
                new DefaultWorkflowActorMapper(),
                event -> {
                    throw new IllegalStateException("observer broke");
                },
                events::add);

        // When a command is recorded with no actor
        Future<Long> result = actors.recording("START", actor -> Future.succeededFuture(5L));

        // Then the recording succeeded and the other observer still heard the event
        assertThat(result.succeeded()).isTrue();
        assertThat(events).containsExactly(new WorkflowExecutorEvent.ActorAbsent("START"));
    }

    @Test
    @DisplayName("an observer that throws an assertion error neither reaches the recording nor stops the next observer")
    void anAssertionErrorFromAnObserverIsIsolated() {
        // Given two observers, the first of which fails an assertion
        List<WorkflowExecutorEvent> events = new ArrayList<>();
        RecordedActors actors = actors(
                holding(null),
                new DefaultWorkflowActorMapper(),
                event -> {
                    throw new AssertionError("observer assertion failed");
                },
                events::add);

        // When a command is recorded with no actor
        Future<Long> result = actors.recording("START", actor -> Future.succeededFuture(6L));

        // Then the recording succeeded and the other observer still heard the event
        assertThat(result.succeeded()).isTrue();
        assertThat(events).containsExactly(new WorkflowExecutorEvent.ActorAbsent("START"));
    }
}
