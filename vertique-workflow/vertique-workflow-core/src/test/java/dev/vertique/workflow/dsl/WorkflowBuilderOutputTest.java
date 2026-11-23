// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowType;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Verifies the {@code output(Class, Function)} declaration of {@link WorkflowBuilder}, its plan and its hash. */
class WorkflowBuilderOutputTest {

    record OrderState(String orderId, String trackingCode) {}

    record StartCmd(String orderId) {}

    @WorkflowType("order.Receipt")
    record Receipt(String orderId, String trackingCode) {}

    record PlainReceipt(String orderId) {}

    @WorkflowContract
    interface OrderContract {}

    private static final Function<OrderState, Receipt> TO_RECEIPT = s -> new Receipt(s.orderId(), s.trackingCode());

    private static WorkflowBuilder<OrderState> baseBuilder() {
        WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, cmd -> new OrderState(cmd.orderId(), null))
                .initialStep("done")
                .complete("done");
        return wf;
    }

    private static WorkflowPlan build(WorkflowBuilder<OrderState> wf) {
        return wf.build("order", 1L, OrderState.class.getName());
    }

    @Test
    void aSecondOutputCallIsRefused() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        wf.output(Receipt.class, TO_RECEIPT);

        assertThatIllegalStateException()
                .isThrownBy(() -> wf.output(Receipt.class, TO_RECEIPT))
                .withMessageContaining("output(...)");
    }

    @Test
    void voidAndNullArgumentsAreRefused() {
        WorkflowBuilder<OrderState> wf = baseBuilder();

        assertThatIllegalArgumentException().isThrownBy(() -> wf.output(Void.class, s -> null));
        @SuppressWarnings("unchecked")
        Class<Object> primitiveVoid = (Class<Object>) (Class<?>) void.class;
        assertThatIllegalArgumentException().isThrownBy(() -> wf.output(primitiveVoid, s -> null));
        assertThatNullPointerException().isThrownBy(() -> wf.output(null, TO_RECEIPT));
        assertThatNullPointerException().isThrownBy(() -> wf.output(Receipt.class, null));
    }

    @Test
    void thePlanNamesTheOutputTypeAndMapperCallback() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        wf.output(Receipt.class, TO_RECEIPT);

        WorkflowPlan plan = build(wf);

        assertThat(plan.outputTypeName()).isEqualTo("order.Receipt");
        assertThat(plan.outputMapperCallbackId()).isEqualTo(new CallbackId("$output.mapper"));
    }

    @Test
    void anUnannotatedOutputClassIsNamedByItsBinaryName() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        wf.output(PlainReceipt.class, s -> new PlainReceipt(s.orderId()));

        assertThat(build(wf).outputTypeName()).isEqualTo(PlainReceipt.class.getName());
    }

    @Test
    void aPlanWithoutAnOutputHasNoOutputComponents() {
        WorkflowPlan plan = build(baseBuilder());

        assertThat(plan.outputTypeName()).isNull();
        assertThat(plan.outputMapperCallbackId()).isNull();
    }

    @Test
    void theOutputChangesThePlanHashButTheMapperBodyDoesNot() {
        WorkflowBuilder<OrderState> withOutput = baseBuilder();
        withOutput.output(Receipt.class, TO_RECEIPT);
        WorkflowBuilder<OrderState> otherBody = baseBuilder();
        otherBody.output(Receipt.class, s -> new Receipt("other", "other"));

        String without = build(baseBuilder()).planHash();

        assertThat(build(withOutput).planHash()).isNotEqualTo(without);
        assertThat(build(otherBody).planHash()).isEqualTo(build(withOutput).planHash());
    }

    @Test
    void theOutputTypeNameChangesThePlanHash() {
        WorkflowBuilder<OrderState> annotated = baseBuilder();
        annotated.output(Receipt.class, TO_RECEIPT);
        WorkflowBuilder<OrderState> plain = baseBuilder();
        plain.output(PlainReceipt.class, s -> new PlainReceipt(s.orderId()));

        assertThat(build(annotated).planHash()).isNotEqualTo(build(plain).planHash());
    }

    @Test
    void theHashHelperAgreesWithTheBuilderForAPlanWithAnOutput() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        wf.output(Receipt.class, TO_RECEIPT);
        WorkflowPlan plan = build(wf);

        assertThat(WorkflowBuilder.computePlanHashForTesting(plan)).isEqualTo(plan.planHash());
    }

    @Test
    void theCallbackRegistryHoldsTheMapper() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        wf.output(Receipt.class, TO_RECEIPT);
        WorkflowPlan plan = build(wf);

        WorkflowCallbackRegistry callbacks = wf.callbackRegistry();

        Object output = callbacks
                .<OrderState>outputMapper(plan.outputMapperCallbackId())
                .apply(new OrderState("o-1", "TRK9"));
        assertThat(output).isEqualTo(new Receipt("o-1", "TRK9"));
    }

    @Test
    void aRegistryWithoutAnOutputMapperRefusesTheLookup() {
        WorkflowCallbackRegistry callbacks = baseBuilder().callbackRegistry();

        assertThatThrownBy(() -> callbacks.outputMapper(new CallbackId("$output.mapper")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theNameBindingHoldsTheOutputClass() {
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>(names);
        wf.init(StartCmd.class, cmd -> new OrderState(cmd.orderId(), null))
                .output(Receipt.class, TO_RECEIPT)
                .initialStep("done")
                .complete("done");

        Map<String, String> binding = wf.nameBinding(OrderState.class);

        assertThat(binding).containsEntry(Receipt.class.getName(), "order.Receipt");
    }

    @Test
    void theOutputClassIsReportedForIndexing() {
        WorkflowBuilder<OrderState> wf = baseBuilder();
        assertThat(wf.outputType()).isNull();

        wf.output(Receipt.class, TO_RECEIPT);

        assertThat(wf.outputType()).isEqualTo(Receipt.class);
    }

    @Test
    void registeringADefinitionWithAnOutputStoresTheNameOnThePlan() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new OutputDefinition(1L, true));

        RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "order-output", 1L));

        assertThat(rw.plan().outputTypeName()).isEqualTo("order.Receipt");
        assertThat(rw.callbacks()
                        .<OrderState>outputMapper(rw.plan().outputMapperCallbackId())
                        .apply(new OrderState("o-1", "TRK9")))
                .isEqualTo(new Receipt("o-1", "TRK9"));
    }

    @Test
    void aDefinitionWithoutAnOutputRegistersExactlyAsBefore() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new OutputDefinition(1L, false));

        RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "order-output", 1L));

        assertThat(rw.plan().outputTypeName()).isNull();
    }

    private static final class OutputDefinition
            implements dev.vertique.workflow.dsl.WorkflowDefinition<OrderState, OrderContract> {

        private final long version;
        private final boolean withOutput;

        OutputDefinition(long version, boolean withOutput) {
            this.version = version;
            this.withOutput = withOutput;
        }

        @Override
        public Class<OrderContract> contract() {
            return OrderContract.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "order-output";
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(StartCmd.class, cmd -> new OrderState(cmd.orderId(), null))
                    .initialStep("done")
                    .complete("done");
            if (withOutput) {
                wf.output(Receipt.class, TO_RECEIPT);
            }
        }
    }
}
