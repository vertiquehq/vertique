// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowClientsModuleEmitter} generates a {@code GeneratedWorkflowClientsModule}
 * whose {@code @Provides @Singleton} methods delegate to {@code WorkflowClientFactory.create(…)}
 * (invariant D5 — the factory must mediate every binding to keep registry/plan validation active).
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Two valid contracts in the same package produce a module at the LCP package with correct
 *       provider methods and factory delegation; the module does NOT contain a direct proxy
 *       construction.</li>
 *   <li>The {@code -Avertique.codegen.package} option overrides the package for the module.</li>
 *   <li>Two contracts with the same simple name collide, cause a compilation error, and prevent
 *       module emission.</li>
 * </ul>
 */
class WorkflowClientsModuleEmitterTest {

    private static final String MODULE_FQN = "com.example.GeneratedWorkflowClientsModule";
    private static final String MODULE_FQN_OVERRIDE = "com.acme.gen.GeneratedWorkflowClientsModule";

    // --- Shared payload fixtures ---

    private static JavaFileObject idempotencyKeyedPayload(String pkg, String simpleName, String keyField) {
        return SourceFiles.inline(pkg + "." + simpleName, """
                        package %s;
                        import dev.vertique.workflow.contract.IdempotencyKeyed;
                        public record %s(String %s) implements IdempotencyKeyed {
                            @Override public String idempotencyKey() { return %s; }
                        }
                        """.formatted(pkg, simpleName, keyField, keyField));
    }

    private static JavaFileObject orderWorkflowContract() {
        return SourceFiles.inline("com.example.OrderWorkflow", """
                        package com.example;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "orders", definitionVersion = 1L)
                        public interface OrderWorkflow {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartOrderPayload cmd);
                        }
                        """);
    }

    private static JavaFileObject paymentWorkflowContract() {
        return SourceFiles.inline("com.example.PaymentWorkflow", """
                        package com.example;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "payments", definitionVersion = 1L)
                        public interface PaymentWorkflow {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartPaymentPayload cmd);
                        }
                        """);
    }

    // --- Tests ---

    @Test
    @DisplayName("two valid contracts in same package generate module with provider methods delegating to factory")
    void twoValidContractsGenerateModule() {
        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        idempotencyKeyedPayload("com.example", "StartOrderPayload", "orderId"),
                        idempotencyKeyedPayload("com.example", "StartPaymentPayload", "paymentId"),
                        orderWorkflowContract(),
                        paymentWorkflowContract())
                .assertSuccess()
                // Module is annotated with @Module
                .assertGeneratedSourceContains(MODULE_FQN, "@Module")
                // Provider methods are present
                .assertGeneratedSourceContains(MODULE_FQN, "provideOrderWorkflow")
                .assertGeneratedSourceContains(MODULE_FQN, "providePaymentWorkflow")
                // Factory parameter is present
                .assertGeneratedSourceContains(MODULE_FQN, "WorkflowClientFactory factory")
                // Bodies delegate to factory (D5 invariant)
                .assertGeneratedSourceContains(MODULE_FQN, "return factory.create(OrderWorkflow.class);")
                .assertGeneratedSourceContains(MODULE_FQN, "return factory.create(PaymentWorkflow.class);")
                // D5: must NOT construct the proxy directly
                .assertGeneratedSourceDoesNotContain(MODULE_FQN, "new OrderWorkflow_WorkflowClientProxy");
    }

    @Test
    @DisplayName("package override option places the module in the overridden package")
    void packageOverridePlacesModuleInOverriddenPackage() {
        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        Map.of("vertique.codegen.package", "com.acme.gen"),
                        idempotencyKeyedPayload("com.example", "StartOrderPayload", "orderId"),
                        orderWorkflowContract())
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "@Module")
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "provideOrderWorkflow")
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "return factory.create(OrderWorkflow.class);");
    }

    @Test
    @DisplayName("two contracts with the same simple name cause a collision error and prevent module emission")
    void simpleNameCollisionCausesErrorAndPreventsEmit() {
        JavaFileObject fooPayloadA = idempotencyKeyedPayload("com.foo", "StartFooPayload", "id");
        JavaFileObject fooPayloadB = idempotencyKeyedPayload("com.bar", "StartBarPayload", "id");
        JavaFileObject contractA = SourceFiles.inline("com.foo.Wf", """
                        package com.foo;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "foo", definitionVersion = 1L)
                        public interface Wf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartFooPayload cmd);
                        }
                        """);
        JavaFileObject contractB = SourceFiles.inline("com.bar.Wf", """
                        package com.bar;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "bar", definitionVersion = 1L)
                        public interface Wf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartBarPayload cmd);
                        }
                        """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), fooPayloadA, fooPayloadB, contractA, contractB)
                .assertFailed()
                .assertErrorMessage("collides with");
    }

    @Test
    @DisplayName("a contract not visible from the module package is skipped, not emitted into a broken module")
    void contractInvisibleFromModulePackageIsSkipped() {
        // com.foo + com.bar resolve the module to package "com", from which the package-private
        // com.foo.HiddenWf cannot be named. Emitting its binding produced a module that did not
        // compile, and because javac compiles generated sources in the same task that broke the
        // application build on processor upgrade alone — no @Component change required.
        JavaFileObject hiddenPayload = idempotencyKeyedPayload("com.foo", "StartFooPayload", "id");
        JavaFileObject visiblePayload = idempotencyKeyedPayload("com.bar", "StartBarPayload", "id");
        JavaFileObject hidden = SourceFiles.inline("com.foo.HiddenWf", """
                        package com.foo;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "foo", definitionVersion = 1L)
                        interface HiddenWf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartFooPayload cmd);
                        }
                        """);
        JavaFileObject visible = SourceFiles.inline("com.bar.VisibleWf", """
                        package com.bar;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "bar", definitionVersion = 1L)
                        public interface VisibleWf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartBarPayload cmd);
                        }
                        """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), hiddenPayload, visiblePayload, hidden, visible)
                .assertSuccess()
                .assertWarningMessage("com.foo.HiddenWf is not accessible from package 'com'")
                .assertGeneratedSourceContains("com.GeneratedWorkflowClientsModule", "provideVisibleWf")
                .assertGeneratedSourceDoesNotContain("com.GeneratedWorkflowClientsModule", "HiddenWf");
    }

    @Test
    @DisplayName("a package-private contract is still bound when the module lands in its own package")
    void packagePrivateContractIsBoundWithinItsOwnPackage() {
        JavaFileObject payload = idempotencyKeyedPayload("com.foo", "StartFooPayload", "id");
        JavaFileObject hidden = SourceFiles.inline("com.foo.HiddenWf", """
                        package com.foo;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "foo", definitionVersion = 1L)
                        interface HiddenWf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartFooPayload cmd);
                        }
                        """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), payload, hidden)
                .assertSuccess()
                .assertGeneratedSourceContains("com.foo.GeneratedWorkflowClientsModule", "provideHiddenWf");
    }

    @Test
    @DisplayName("a simple-name clash with a skipped contract does not fail the build")
    void collisionWithSkippedContractDoesNotFail() {
        // com.foo.Wf is package-private and unreferenceable from the resolved package "com", so it
        // is skipped and never produces a provider method — meaning it cannot collide with
        // com.bar.Wf. Checking collisions before filtering hard-failed the whole module here.
        JavaFileObject fooPayload = idempotencyKeyedPayload("com.foo", "StartFooPayload", "id");
        JavaFileObject barPayload = idempotencyKeyedPayload("com.bar", "StartBarPayload", "id");
        JavaFileObject hidden = SourceFiles.inline("com.foo.Wf", """
                        package com.foo;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "foo", definitionVersion = 1L)
                        interface Wf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartFooPayload cmd);
                        }
                        """);
        JavaFileObject visible = SourceFiles.inline("com.bar.Wf", """
                        package com.bar;
                        import dev.vertique.workflow.contract.WorkflowContract;
                        import dev.vertique.workflow.contract.WorkflowStart;
                        import dev.vertique.workflow.ops.WorkflowInstanceId;
                        import io.vertx.core.Future;
                        @WorkflowContract(definitionId = "bar", definitionVersion = 1L)
                        public interface Wf {
                            @WorkflowStart
                            Future<WorkflowInstanceId> start(StartBarPayload cmd);
                        }
                        """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), fooPayload, barPayload, hidden, visible)
                .assertSuccess()
                .assertGeneratedSourceContains("com.GeneratedWorkflowClientsModule", "provideWf");
    }
}
