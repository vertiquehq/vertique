// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowTypeNameConflictException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowContributor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared type-name index as the registry assembles it at startup: names are unique per namespace, a
 * logical name never shadows another class's binary name, lookups never load classes, and invalid names are refused.
 */
class WorkflowTypeNamesTest {

    // --- Fixture types, all at the top level of this class so their binary names stay predictable ---

    private static final String PLAIN_BINARY_NAME = "dev.vertique.workflow.types.WorkflowTypeNamesTest$Plain";

    private static final String FIFTY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NAME_OF_255_CODE_POINTS = FIFTY + FIFTY + FIFTY + FIFTY + FIFTY + "aaaaa";
    private static final String NAME_OF_256_CODE_POINTS = NAME_OF_255_CODE_POINTS + "a";

    record Start(String id) {}

    record Plain(String id) {}

    record Unindexed(String id) {}

    @WorkflowType("order")
    record Alpha(String id) {}

    @WorkflowType("order")
    record Beta(String id) {}

    @WorkflowType("shared")
    record SharedState(String id) {}

    @WorkflowType(PLAIN_BINARY_NAME)
    record Imposter(String id) {}

    @WorkflowType("   ")
    record BlankNamed(String id) {}

    @WorkflowType(NAME_OF_256_CODE_POINTS)
    record TooLongNamed(String id) {}

    @WorkflowType(NAME_OF_255_CODE_POINTS)
    record LongestNamed(String id) {}

    @WorkflowContract
    interface FirstContract {}

    @WorkflowContract
    interface SecondContract {}

    @WorkflowContract("orders")
    interface LedgerOneContract {}

    @WorkflowContract("orders")
    interface LedgerTwoContract {}

    @WorkflowContract("shared")
    interface SharedContract {}

    // --- Duplicate names ---

    @Nested
    @DisplayName("two state classes under one logical name")
    class TwoStatesUnderOneName {

        @Test
        @DisplayName("the second registration fails startup naming the name and both classes")
        void duplicateNamesFailRegistryStartupPerNamespace() {
            // Given: two definitions whose different state classes are both named order
            WorkflowContributor first = registering(definition("alpha-saga", FirstContract.class, Alpha.class));
            WorkflowContributor second = registering(definition("beta-saga", SecondContract.class, Beta.class));

            // When / Then: startup fails with a conflict naming the logical name and both binary class names
            assertThatThrownBy(() -> startUp(first, second))
                    .isInstanceOf(WorkflowTypeNameConflictException.class)
                    .hasMessageContaining("order")
                    .hasMessageContaining(Alpha.class.getName())
                    .hasMessageContaining(Beta.class.getName());
        }
    }

    @Nested
    @DisplayName("two contracts under one logical name")
    class TwoContractsUnderOneName {

        @Test
        @DisplayName("the second registration fails startup naming the name and both contracts")
        void duplicateNamesFailRegistryStartupPerNamespace() {
            // Given: two definitions whose different contracts are both named orders
            WorkflowContributor first = registering(definition("ledger-one", LedgerOneContract.class, Plain.class));
            WorkflowContributor second = registering(definition("ledger-two", LedgerTwoContract.class, Plain.class));

            // When / Then: startup fails with a conflict naming the logical name and both binary class names
            assertThatThrownBy(() -> startUp(first, second))
                    .isInstanceOf(WorkflowTypeNameConflictException.class)
                    .hasMessageContaining("orders")
                    .hasMessageContaining(LedgerOneContract.class.getName())
                    .hasMessageContaining(LedgerTwoContract.class.getName());
        }
    }

    @Nested
    @DisplayName("a contract and a state class under one logical name")
    class ContractAndStateUnderOneName {

        @Test
        @DisplayName("the namespaces are separate, so startup succeeds and each lookup finds its own class")
        void duplicateNamesFailRegistryStartupPerNamespace() {
            // Given: one definition whose contract and state class are both named shared
            WorkflowContributor contributor =
                    registering(definition("shared-saga", SharedContract.class, SharedState.class));

            // When: startup runs
            DefaultWorkflowTypeNames names = startUp(contributor);

            // Then: the name resolves to the state class as a type and to the interface as a contract
            assertThat(names.find("shared")).hasValue(SharedState.class);
            assertThat(names.findContract("shared")).hasValue(SharedContract.class);
        }
    }

    @Nested
    @DisplayName("a logical name equal to the binary name of another class")
    class LogicalNameEqualsBinaryName {

        @Test
        @DisplayName("startup fails naming the shared name and both classes, whichever class is indexed first")
        void duplicateNamesFailRegistryStartupPerNamespace() {
            // Given: a state class named with the binary name of Plain, and Plain itself as another state class
            assertThat(Plain.class.getName()).isEqualTo(PLAIN_BINARY_NAME);
            WorkflowContributor plain = registering(definition("plain-saga", FirstContract.class, Plain.class));
            WorkflowContributor imposter =
                    registering(definition("imposter-saga", SecondContract.class, Imposter.class));

            // When / Then: startup fails with the conflict in both registration orders
            assertBinaryNameConflict("Plain first", plain, imposter);
            assertBinaryNameConflict("Imposter first", imposter, plain);
        }

        private void assertBinaryNameConflict(String order, WorkflowContributor first, WorkflowContributor second) {
            assertThatThrownBy(() -> startUp(first, second))
                    .as(order)
                    .isInstanceOf(WorkflowTypeNameConflictException.class)
                    .hasMessageContaining(PLAIN_BINARY_NAME)
                    .hasMessageContaining(Imposter.class.getName());
        }
    }

    // --- Lookups ---

    @Test
    @DisplayName("lookups find logical and indexed binary names, refuse unknown names and never load a class")
    void resolvesLogicalAndIndexedBinaryNamesAndRefusesUnknown() {
        assertThat(NAME_OF_255_CODE_POINTS).hasSize(255);
        assertThat(NAME_OF_256_CODE_POINTS).hasSize(256);

        // Given: an index holding Alpha under its logical name and Plain under its binary name, and a context class
        // loader that records every class name it is asked for
        DefaultWorkflowTypeNames names = startUp(
                registering(definition("alpha-saga", FirstContract.class, Alpha.class)),
                registering(definition("plain-saga", SecondContract.class, Plain.class)));
        RecordingLoader loader = new RecordingLoader(getClass().getClassLoader());
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            // When / Then: the logical name and both binary names of indexed classes resolve
            assertThat(names.resolve("order")).isEqualTo(Alpha.class);
            assertThat(names.resolve(Alpha.class.getName())).isEqualTo(Alpha.class);
            assertThat(names.resolve(Plain.class.getName())).isEqualTo(Plain.class);

            // And: an unknown name is absent from find and refused by resolve, naming the name
            assertThat(names.find("missing")).isEmpty();
            assertThatThrownBy(() -> names.resolve("missing"))
                    .isInstanceOf(WorkflowUnknownTypeNameException.class)
                    .hasMessageContaining("missing");

            // And: the binary name of a loadable class that was never indexed is absent too
            assertThat(names.find(Unindexed.class.getName())).isEmpty();

            // And: no lookup asked the context class loader for a class
            assertThat(loader.requested).doesNotContain("missing", Unindexed.class.getName());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }

        // When / Then: a blank name and a name of 256 code points are refused naming the class, never the raw value
        assertThatThrownBy(() -> startUp(registering(definition("blank-saga", FirstContract.class, BlankNamed.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining(BlankNamed.class.getName());
        assertThatThrownBy(() -> startUp(registering(definition("long-saga", FirstContract.class, TooLongNamed.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining(TooLongNamed.class.getName())
                .message()
                .doesNotContain(NAME_OF_256_CODE_POINTS);

        // And: a name of exactly 255 code points is accepted
        assertThatCode(() -> startUp(registering(definition("limit-saga", FirstContract.class, LongestNamed.class))))
                .doesNotThrowAnyException();
    }

    // --- Helpers ---

    /** Assembles a registry over one shared index and runs the contributors, as the core module does at startup. */
    private static DefaultWorkflowTypeNames startUp(WorkflowContributor... contributors) {
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(), names);
        for (WorkflowContributor contributor : contributors) {
            contributor.contribute(registry);
        }
        return names;
    }

    private static WorkflowContributor registering(WorkflowDefinition<?, ?> definition) {
        return registry -> registry.register(definition);
    }

    /** A definition with the given id, contract and state class that starts and completes at once. */
    private static <S, C> WorkflowDefinition<S, C> definition(String id, Class<C> contract, Class<S> state) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<S> stateType() {
                return state;
            }

            @Override
            public String definitionId() {
                return id;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<S> wf) {
                wf.init(Start.class, start -> null).initialStep("done").complete("done");
            }
        };
    }

    /** A loader that delegates to its parent and records the class names it is asked for. */
    private static final class RecordingLoader extends ClassLoader {

        final List<String> requested = new ArrayList<>();

        RecordingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            requested.add(name);
            return super.loadClass(name);
        }
    }
}
