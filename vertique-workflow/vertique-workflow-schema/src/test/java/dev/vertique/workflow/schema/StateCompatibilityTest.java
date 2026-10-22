// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the schema module's {@link WorkflowStateCompatibility}: a scope-owned state type extends the default
 * scope's when every property of the source exists in the target with the same type, and the registry applies the
 * comparison when the second of the two definitions registers.
 *
 * <p>The comparison and the registry come from a Dagger component over the public modules, so the proofs run
 * through the same binding an application gets.
 */
class StateCompatibilityTest {

    // --- Wiring ---

    /** The bindings the application owes the schema module. */
    @Module
    abstract static class JsonBindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, WorkflowSchemaModule.class, JsonBindings.class})
    interface Wired {

        WorkflowRegistry registry();

        Optional<WorkflowStateCompatibility> compatibility();
    }

    private static WorkflowRegistry freshRegistry() {
        return DaggerStateCompatibilityTest_Wired.create().registry();
    }

    private static WorkflowStateCompatibility comparison() {
        return DaggerStateCompatibilityTest_Wired.create()
                .compatibility()
                .orElseThrow(() -> new AssertionError("the schema module binds no state comparison"));
    }

    // --- Fixture: registry level ---

    @WorkflowContract
    interface ZContract {}

    record Start(String id) {}

    record Base(String id, int amount) {}

    record Added(String id, int amount, String note) {}

    record Removed(String id) {}

    record Retyped(String id, String amount) {}

    private static <S> WorkflowDefinition<S, ZContract> definition(Class<S> state, Function<String, S> initial) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<ZContract> contract() {
                return ZContract.class;
            }

            @Override
            public Class<S> stateType() {
                return state;
            }

            @Override
            public String definitionId() {
                return "s-z";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<S> wf) {
                wf.init(Start.class, start -> initial.apply(start.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    private static WorkflowDefinition<Base, ZContract> base() {
        return definition(Base.class, id -> new Base(id, 0));
    }

    private static WorkflowDefinition<Added, ZContract> added() {
        return definition(Added.class, id -> new Added(id, 0, null));
    }

    private static WorkflowDefinition<Removed, ZContract> removed() {
        return definition(Removed.class, Removed::new);
    }

    private static WorkflowDefinition<Retyped, ZContract> retyped() {
        return definition(Retyped.class, id -> new Retyped(id, "0"));
    }

    // --- Fixture: nested shapes ---

    enum Kind {
        A,
        B
    }

    enum KindWithoutB {
        A
    }

    enum KindWithC {
        A,
        B,
        C
    }

    record Address(String city) {}

    record AddressPlus(String city, String zip) {}

    record AddressWithoutCity(String zip) {}

    record Item(String sku) {}

    record ItemPlus(String sku, int quantity) {}

    record ItemRetyped(int sku) {}

    record Source(Address address, List<Item> items, Kind kind) {}

    record AddsNestedProperties(AddressPlus address, List<ItemPlus> items, Kind kind) {}

    record RemovesAddressCity(AddressWithoutCity address, List<Item> items, Kind kind) {}

    record RetypesItemSku(Address address, List<ItemRetyped> items, Kind kind) {}

    record RemovesKindConstant(Address address, List<Item> items, KindWithoutB kind) {}

    record AddsKindConstant(Address address, List<Item> items, KindWithC kind) {}

    record PlainKind(String id, String kind) {}

    record EnumeratedKind(String id, Kind kind) {}

    // --- Fixture: older state JSON ---

    record Order(String id) {}

    record OrderPlus(String id, @Nullable String note) {}

    // --- Tests ---

    static Stream<Arguments> candidates() {
        return Stream.of(
                Arguments.of("added property", (Supplier<WorkflowDefinition<?, ?>>) StateCompatibilityTest::added, ""),
                Arguments.of(
                        "removed property",
                        (Supplier<WorkflowDefinition<?, ?>>) StateCompatibilityTest::removed,
                        "amount"),
                Arguments.of(
                        "retyped property",
                        (Supplier<WorkflowDefinition<?, ?>>) StateCompatibilityTest::retyped,
                        "amount"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    @DisplayName("a scope-owned state type must extend the default state type")
    void scopeOwnedStateMustExtendDefaultState(
            String name, Supplier<WorkflowDefinition<?, ?>> candidate, String offendingPath) {
        // Given: a registry with the schema comparison and (default, s-z, 1) with Base(id, amount)
        WorkflowRegistry registry = freshRegistry();
        registry.register(base());

        // When: the candidate registers as (acme, s-z, 1)
        Throwable scopeFirst = null;
        try {
            registry.register("acme", candidate.get());
        } catch (RuntimeException e) {
            scopeFirst = e;
        }

        // and: in another registry the candidate registers in acme first and Base in default second
        WorkflowRegistry reversed = freshRegistry();
        reversed.register("acme", candidate.get());
        Throwable defaultSecond = null;
        try {
            reversed.register(base());
        } catch (RuntimeException e) {
            defaultSecond = e;
        }

        // Then: an added property is accepted in both orders
        if (offendingPath.isEmpty()) {
            assertThat(scopeFirst).isNull();
            assertThat(defaultSecond).isNull();
            return;
        }

        // and: a removed or retyped property fails naming the scope, the id and the property path
        assertThat(scopeFirst).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(scopeFirst.getMessage()).contains("acme").contains("s-z").contains(offendingPath);

        // and: in the reversed order the default registration fails the same way
        assertThat(defaultSecond).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(defaultSecond.getMessage()).contains("acme").contains("s-z").contains(offendingPath);
    }

    static Stream<Arguments> nestedTargets() {
        return Stream.of(
                Arguments.of("property added to Address and Item", AddsNestedProperties.class, Optional.empty()),
                Arguments.of("city removed from Address", RemovesAddressCity.class, Optional.of("address.city")),
                Arguments.of("Item.sku retyped", RetypesItemSku.class, Optional.of("items[].sku")),
                Arguments.of("constant B removed from Kind", RemovesKindConstant.class, Optional.of("kind")),
                Arguments.of("constant C added to Kind", AddsKindConstant.class, Optional.empty()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedTargets")
    @DisplayName("nested objects, collections and enums compare by the same rule")
    void nestedObjectsCollectionsAndEnumsCompareByTheSameRule(
            String name, Class<?> target, Optional<String> expectedPath) {
        // Given: a source type with a nested record, a list of records and an enum
        WorkflowStateCompatibility comparison = comparison();

        // When: the source is compared with the target
        Optional<String> incompatibility = comparison.firstIncompatibility(Source.class, target);

        // Then: the first incompatible path is reported, or nothing when the target extends the source
        assertThat(incompatibility).isEqualTo(expectedPath);
    }

    @Test
    @DisplayName("a property added by a newer state type reads from older state JSON")
    void propertyAddedByNewerStateTypeDeserializesFromOlderStateJson() {
        // Given: Order(id), OrderPlus(id, note) and state JSON written before the property existed
        WorkflowStateCompatibility comparison = comparison();
        Buffer olderState = Buffer.buffer("{\"id\":\"o-1\"}");

        // When: the comparison reports on the pair and the JSON is decoded as OrderPlus
        Optional<String> incompatibility = comparison.firstIncompatibility(Order.class, OrderPlus.class);
        OrderPlus decoded = Json.decodeValue(olderState, OrderPlus.class);

        // Then: nothing is incompatible and the added property reads as absent
        assertThat(incompatibility).isEmpty();
        assertThat(decoded.id()).isEqualTo("o-1");
        assertThat(decoded.note()).isNull();
    }

    @Test
    @DisplayName("a free text property is not compatible with a target that restricts it to enum constants")
    void freeTextPropertyIsNotCompatibleWithAnEnumTarget() {
        // Given: a source whose kind is free text and a target whose kind is an enum, and a target whose kind is
        // free text as well
        WorkflowStateCompatibility comparison = comparison();

        // When: the source is compared with each target
        Optional<String> restricted = comparison.firstIncompatibility(PlainKind.class, EnumeratedKind.class);
        Optional<String> unchanged = comparison.firstIncompatibility(PlainKind.class, PlainKind.class);

        // Then: the enum target is refused at the property, state JSON with any other text could not be read
        assertThat(restricted).contains("kind");

        // and: an unchanged free text property is accepted
        assertThat(unchanged).isEmpty();
    }
}
