// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CorrelationKeyResolverRegistry} and its {@link DefaultCorrelationKeyResolverRegistry}
 * implementation against the shape of the timer resolver registry, and the eleventh registry on
 * {@link RegisteredIdentifierLookup}.
 */
class CorrelationKeyResolverRegistryTest {

    record _State(String carrierRef) {}

    private static final Function<_State, String> CARRIER_REF = _State::carrierRef;
    private static final Function<_State, String> OTHER_KEY = s -> "other";

    private static final NamedCorrelationKeyResolver<_State> NAMED =
            new NamedCorrelationKeyResolver<>("order.carrierRef", _State.class, CARRIER_REF);

    private static CorrelationKeyResolverRegistry registry(CorrelationKeyResolverContributor... contributors) {
        return new DefaultCorrelationKeyResolverRegistry(Set.of(contributors));
    }

    /** The ten existing registries, all empty, plus the one given as the eleventh. */
    private static RegisteredIdentifierLookup lookupWithEleventh(CorrelationKeyResolverRegistry correlationKeys) {
        return new RegisteredIdentifierLookup(
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
                correlationKeys);
    }

    /** The ten-argument constructor that existed before the eleventh registry. */
    private static RegisteredIdentifierLookup lookupWithTenArguments() {
        return new RegisteredIdentifierLookup(
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
    }

    @Test
    @DisplayName("the registry looks up, contains and lists registered resolvers, refuses a duplicate id, "
            + "and the lookup exposes it through the new constructor only")
    @SuppressWarnings("unchecked")
    void registryLooksUpContainsAndListsRegisteredResolvers() {
        // Given: a default registry built from a contributor that registers order.carrierRef
        CorrelationKeyResolverRegistry registry = registry(b -> b.register(NAMED));

        // When: the registry is queried
        NamedCorrelationKeyResolver<_State> found =
                (NamedCorrelationKeyResolver<_State>) registry.lookup("order.carrierRef");

        // Then: lookup returns the resolver, whose function maps a state to its key; contains and ids agree
        assertThat(found).isEqualTo(NAMED);
        assertThat(found.resolver().apply(new _State("CR-9"))).isEqualTo("CR-9");
        assertThat(registry.contains("order.carrierRef")).isTrue();
        assertThat(registry.contains("order.missing")).isFalse();
        assertThat(registry.ids()).containsExactly("order.carrierRef");
        assertThat(registry().ids()).isEmpty();

        // And: a second resolver under the same id is refused, as the timer registry refuses it
        NamedCorrelationKeyResolver<_State> conflicting =
                new NamedCorrelationKeyResolver<>("order.carrierRef", _State.class, OTHER_KEY);
        assertThatThrownBy(() -> registry(b -> b.register(NAMED), b -> b.register(conflicting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order.carrierRef");

        // And: the new constructor exposes the registry it was given
        assertThat(lookupWithEleventh(registry).correlationKeyResolvers()).isSameAs(registry);
        assertThat(lookupWithEleventh(registry).correlationKeyResolvers().contains("order.carrierRef"))
                .isTrue();

        // And: the ten-argument constructor exposes an empty registry
        assertThat(lookupWithTenArguments().correlationKeyResolvers().ids()).isEmpty();
    }
}
