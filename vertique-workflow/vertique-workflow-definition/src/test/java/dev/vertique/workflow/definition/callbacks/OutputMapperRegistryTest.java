// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Verifies {@link NamedOutputMapper}, {@link DefaultOutputMapperRegistry} and the twelfth registry of the lookup. */
class OutputMapperRegistryTest {

    record State(String id) {}

    record Receipt(String id) {}

    private static final NamedOutputMapper<State, Receipt> NAMED =
            new NamedOutputMapper<>("order.receipt", State.class, Receipt.class, s -> new Receipt(s.id()));

    private static OutputMapperRegistry registry(OutputMapperContributor... contributors) {
        return new DefaultOutputMapperRegistry(Set.of(contributors));
    }

    @Test
    void theRegistryLooksUpContainsAndListsRegisteredMappers() {
        OutputMapperRegistry registry = registry(b -> b.register(NAMED));

        NamedOutputMapper<?, ?> found = registry.lookup("order.receipt");

        assertThat(found).isEqualTo(NAMED);
        assertThat(found.stateType()).isEqualTo(State.class);
        assertThat(found.outputType()).isEqualTo(Receipt.class);
        assertThat(registry.contains("order.receipt")).isTrue();
        assertThat(registry.contains("order.missing")).isFalse();
        assertThat(registry.ids()).containsExactly("order.receipt");
    }

    @Test
    void anUnknownIdFailsTheLookupAndNamesTheKnownIds() {
        OutputMapperRegistry registry = registry(b -> b.register(NAMED));

        assertThatThrownBy(() -> registry.lookup("order.missing"))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("order.missing")
                .hasMessageContaining("order.receipt");
    }

    @Test
    void anEmptyRegistryIsValid() {
        OutputMapperRegistry registry = registry();

        assertThat(registry.ids()).isEmpty();
        assertThat(registry.contains("any")).isFalse();
    }

    @Test
    void theSameRecordTwiceIsANoOpButADifferentRecordForOneIdIsRefused() {
        assertThat(registry(b -> b.register(NAMED), b -> b.register(NAMED)).ids())
                .containsExactly("order.receipt");

        Function<State, Receipt> other = s -> new Receipt("other");
        NamedOutputMapper<State, Receipt> conflicting =
                new NamedOutputMapper<>("order.receipt", State.class, Receipt.class, other);

        assertThatIllegalStateException()
                .isThrownBy(() -> registry(b -> b.register(NAMED), b -> b.register(conflicting)))
                .withMessageContaining("order.receipt");
    }

    @Test
    void theNamedMapperRefusesNullsAndABlankId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NamedOutputMapper<>(" ", State.class, Receipt.class, s -> new Receipt("x")));
        assertThatNullPointerException()
                .isThrownBy(() -> new NamedOutputMapper<>(null, State.class, Receipt.class, s -> new Receipt("x")));
        assertThatNullPointerException()
                .isThrownBy(() -> new NamedOutputMapper<State, Receipt>("id", null, Receipt.class, s -> null));
        assertThatNullPointerException()
                .isThrownBy(() -> new NamedOutputMapper<State, Receipt>("id", State.class, null, s -> null));
        assertThatNullPointerException()
                .isThrownBy(() -> new NamedOutputMapper<State, Receipt>("id", State.class, Receipt.class, null));
    }

    @Test
    void theLookupExposesTheOutputMappersAndItsEarlierConstructorsPassAnEmptyRegistry() {
        RegisteredIdentifierLookup twelve = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of()),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()),
                new DefaultCorrelationKeyResolverRegistry(Set.of()),
                registry(b -> b.register(NAMED)));
        RegisteredIdentifierLookup eleven = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of()),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()),
                new DefaultCorrelationKeyResolverRegistry(Set.of()));
        RegisteredIdentifierLookup ten = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of()),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        assertThat(twelve.outputMappers().ids()).containsExactly("order.receipt");
        assertThat(eleven.outputMappers().ids()).isEmpty();
        assertThat(ten.outputMappers().ids()).isEmpty();
    }
}
