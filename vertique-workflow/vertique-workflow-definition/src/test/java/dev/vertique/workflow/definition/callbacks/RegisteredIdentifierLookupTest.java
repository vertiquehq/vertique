// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RegisteredIdentifierLookup}: facade accessor methods and
 * the {@code nearestIds} Levenshtein-based suggestion utility.
 */
class RegisteredIdentifierLookupTest {

    record _State(String value) {}

    record _Payload(String raw) {}

    // --- Per-registry trivial fixture entries ---

    private static final NamedPayloadMapper<_State> PAYLOAD_MAPPER =
            new NamedPayloadMapper<>("order.payload", _State.class, s -> s.value());
    private static final NamedStartStateMapper<_Payload, _State> START_STATE_MAPPER =
            new NamedStartStateMapper<>("order.start", _Payload.class, _State.class, p -> new _State(p.raw()));
    private static final NamedStateReducer<_State> STATE_REDUCER =
            new NamedStateReducer<>("order.reduce", _State.class, (s, e) -> s);
    private static final NamedStateMutator<_State> STATE_MUTATOR =
            new NamedStateMutator<>("order.mutate", _State.class, s -> s);
    private static final NamedTimerResolver<_State> TIMER_RESOLVER =
            new NamedTimerResolver<>("order.timer", _State.class, s -> Instant.EPOCH);
    private static final NamedFailMessageFactory<_State> FAIL_MESSAGE_FACTORY =
            new NamedFailMessageFactory<>("order.fail", _State.class, s -> "fail");
    private static final NamedSubjectResolver<_State> SUBJECT_RESOLVER = new NamedSubjectResolver<>(
            "order.subject", _State.class, s -> new dev.vertique.workflow.subject.WorkflowSubjectRef("O", "1", null));
    private static final NamedTaskAssignmentResolver<_State> TASK_ASSIGNMENT_RESOLVER =
            new NamedTaskAssignmentResolver<>(
                    "order.assign", _State.class, s -> new dev.vertique.workflow.tasks.TaskAssignment.User("u1"));
    private static final NamedBranchResultReducer<_State> BRANCH_RESULT_REDUCER =
            new NamedBranchResultReducer<>("order.branch", _State.class, (BiFunction<
                            _State, Map<String, dev.vertique.workflow.plan.BranchResult>, _State>)
                    (s, m) -> s);
    private static final NamedCondition<_State> NAMED_CONDITION =
            new NamedCondition<>("order.approved", _State.class, s -> true);

    private RegisteredIdentifierLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of(b -> b.register(PAYLOAD_MAPPER))),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(START_STATE_MAPPER))),
                new DefaultStateReducerRegistry(Set.of(b -> b.register(STATE_REDUCER))),
                new DefaultStateMutatorRegistry(Set.of(b -> b.register(STATE_MUTATOR))),
                new DefaultTimerResolverRegistry(Set.of(b -> b.register(TIMER_RESOLVER))),
                new DefaultFailMessageFactoryRegistry(Set.of(b -> b.register(FAIL_MESSAGE_FACTORY))),
                new DefaultSubjectResolverRegistry(Set.of(b -> b.register(SUBJECT_RESOLVER))),
                new DefaultTaskAssignmentResolverRegistry(Set.of(b -> b.register(TASK_ASSIGNMENT_RESOLVER))),
                new DefaultBranchResultReducerRegistry(Set.of(b -> b.register(BRANCH_RESULT_REDUCER))),
                new DefaultNamedConditionRegistry(Set.of(b -> b.register(NAMED_CONDITION))));
    }

    // --- Accessor delegation ---

    @Nested
    @DisplayName("registry accessors")
    class Accessors {

        @Test
        @DisplayName("payloadMappers() returns the PayloadMapperRegistry")
        void payloadMappers() {
            assertThat(lookup.payloadMappers().contains("order.payload")).isTrue();
        }

        @Test
        @DisplayName("startStateMappers() returns the StartStateMapperRegistry")
        void startStateMappers() {
            assertThat(lookup.startStateMappers().contains("order.start")).isTrue();
        }

        @Test
        @DisplayName("stateReducers() returns the StateReducerRegistry")
        void stateReducers() {
            assertThat(lookup.stateReducers().contains("order.reduce")).isTrue();
        }

        @Test
        @DisplayName("stateMutators() returns the StateMutatorRegistry")
        void stateMutators() {
            assertThat(lookup.stateMutators().contains("order.mutate")).isTrue();
        }

        @Test
        @DisplayName("timerResolvers() returns the TimerResolverRegistry")
        void timerResolvers() {
            assertThat(lookup.timerResolvers().contains("order.timer")).isTrue();
        }

        @Test
        @DisplayName("failMessageFactories() returns the FailMessageFactoryRegistry")
        void failMessageFactories() {
            assertThat(lookup.failMessageFactories().contains("order.fail")).isTrue();
        }

        @Test
        @DisplayName("subjectResolvers() returns the SubjectResolverRegistry")
        void subjectResolvers() {
            assertThat(lookup.subjectResolvers().contains("order.subject")).isTrue();
        }

        @Test
        @DisplayName("taskAssignmentResolvers() returns the TaskAssignmentResolverRegistry")
        void taskAssignmentResolvers() {
            assertThat(lookup.taskAssignmentResolvers().contains("order.assign"))
                    .isTrue();
        }

        @Test
        @DisplayName("branchResultReducers() returns the BranchResultReducerRegistry")
        void branchResultReducers() {
            assertThat(lookup.branchResultReducers().contains("order.branch")).isTrue();
        }

        @Test
        @DisplayName("namedConditions() returns the NamedConditionRegistry")
        void namedConditions() {
            assertThat(lookup.namedConditions().contains("order.approved")).isTrue();
        }
    }

    // --- nearestIds static utility ---

    @Nested
    @DisplayName("nearestIds")
    class NearestIds {

        @Test
        @DisplayName("returns closest id first by Levenshtein distance")
        void closestIdFirst() {
            List<String> result =
                    RegisteredIdentifierLookup.nearestIds("ordr.payload", List.of("order.payload", "order.reduce"), 5);
            // "order.payload" is closer to "ordr.payload" than "order.reduce"
            assertThat(result).first().isEqualTo("order.payload");
        }

        @Test
        @DisplayName("returns all candidates when within limit")
        void returnsAllWhenWithinLimit() {
            List<String> result =
                    RegisteredIdentifierLookup.nearestIds("ordr.payload", List.of("order.payload", "order.reduce"), 5);
            assertThat(result).containsExactly("order.payload", "order.reduce");
        }

        @Test
        @DisplayName("respects the limit parameter")
        void respectsLimit() {
            List<String> result = RegisteredIdentifierLookup.nearestIds(
                    "ordr.payload", List.of("order.payload", "order.reduce", "order.mutate"), 1);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("returns empty list when registered ids are empty")
        void emptyWhenNoRegisteredIds() {
            assertThat(RegisteredIdentifierLookup.nearestIds("x", List.of(), 5)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when limit is zero")
        void emptyWhenLimitZero() {
            assertThat(RegisteredIdentifierLookup.nearestIds("x", List.of("x"), 0))
                    .isEmpty();
        }

        @Test
        @DisplayName("exact match has distance zero and ranks first")
        void exactMatchRanksFirst() {
            List<String> result =
                    RegisteredIdentifierLookup.nearestIds("order.payload", List.of("order.payload", "order.reduce"), 5);
            assertThat(result).first().isEqualTo("order.payload");
        }
    }
}
