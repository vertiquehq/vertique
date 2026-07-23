// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code ServiceCouplingValidator} mirrors {@code ContractDiscovery.findContract}
 * (handler pattern + direct-implementation pattern + ambiguity rejection) and the unconditional
 * {@code @ServiceOperation} requirement that {@code CronJobRegistrar} enforces at startup.
 */
class CronJobProcessorServiceCouplingTest {

    @Test
    @DisplayName("direct-impl with @ServiceContract + @ServiceOperation on contract method -> success")
    void directImpl_withServiceOperation_succeeds() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertSuccess();
    }

    @Test
    @DisplayName("impl without any @ServiceContract / handler ancestor -> error")
    void implWithoutContract_isError() {
        ProcessorTestHarness.run(new CronJobProcessor(), CronJobProcessorFixtures.implWithoutContract())
                .assertFailed()
                .assertErrorMessage("does not implement any @ServiceContract-annotated interface");
    }

    @Test
    @DisplayName("impl implementing two @ServiceContract interfaces -> ambiguity error")
    void implWithMultipleContracts_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.secondContract(),
                        CronJobProcessorFixtures.implWithTwoContracts())
                .assertFailed()
                .assertErrorMessage("implements multiple @ServiceContract interfaces");
    }

    @Test
    @DisplayName("ServiceHandler<C> impl with @ServiceContract on C and @ServiceOperation on contract -> success")
    void handlerImpl_succeeds() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.handlerImpl())
                .assertSuccess();
    }

    @Test
    @DisplayName("handler that also directly implements C -> error (double pattern)")
    void handlerImplPlusDirect_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.handlerImplPlusDirect())
                .assertFailed()
                .assertErrorMessage("use one pattern, not both");
    }

    @Test
    @DisplayName("contract method missing @ServiceOperation -> unconditional error (regardless of mode/tracked)")
    void missingServiceOperation_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithoutServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("requires the corresponding service contract method to have @ServiceOperation");
    }

    @Test
    @DisplayName("@CronJob placed on a contract interface method -> error (must go on impl)")
    void cronJobOnContractInterface_isError() {
        // Mirror runtime CronJobRegistrar.java:159-162: @CronJob on the contract interface itself
        // is rejected; it must go on the implementation method.
        ProcessorTestHarness.run(
                        new CronJobProcessor(), SourceFiles.inline("dev.vertique.examples.cron.IfaceContract", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import dev.vertique.services.ServiceContract;
                                import dev.vertique.services.ServiceOperation;
                                import io.vertx.core.Future;

                                @ServiceContract("iface-service")
                                public interface IfaceContract {
                                    @ServiceOperation("op")
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    Future<Void> op();
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("Place @CronJob on the implementation method, not the contract interface");
    }

    @Test
    @DisplayName("@ServiceOperation(\"\") on the matched contract method -> error (blank value)")
    void blankServiceOperation_isError() {
        // Mirror runtime OperationIdResolver.java:62-66: @ServiceOperation with a blank value
        // would throw at startup; APT must reject the same way.
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        SourceFiles.inline("dev.vertique.examples.cron.BlankOpContract", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.services.ServiceContract;
                                import dev.vertique.services.ServiceOperation;
                                import io.vertx.core.Future;

                                @ServiceContract("blank-op-service")
                                public interface BlankOpContract {
                                    @ServiceOperation("")
                                    Future<Void> op();
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.cron.BlankOpService", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import io.vertx.core.Future;

                                public class BlankOpService implements BlankOpContract {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    @Override
                                    public Future<Void> op() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("@ServiceOperation with a blank value");
    }

    @Test
    @DisplayName("ServiceHandler raw (no type argument) -> error (contract type parameter unresolved)")
    void serviceHandlerRaw_isError() {
        // Mirrors ContractDiscovery.findContract: a ServiceHandler implementation with no
        // resolvable type argument is rejected because the contract type cannot be inferred.
        ProcessorTestHarness.run(
                        new CronJobProcessor(), SourceFiles.inline("dev.vertique.examples.cron.RawHandlerService", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import dev.vertique.services.ServiceHandler;
                                import io.vertx.core.Future;

                                @SuppressWarnings("rawtypes")
                                public class RawHandlerService implements ServiceHandler {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    public Future<Void> tick() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("contract type parameter could not be resolved");
    }

    @Test
    @DisplayName("ServiceHandler<C> where C lacks @ServiceContract -> error")
    void serviceHandlerWithNonContractType_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        SourceFiles.inline("dev.vertique.examples.cron.NotAContract", """
                                package dev.vertique.examples.cron;

                                public interface NotAContract {}
                                """),
                        SourceFiles.inline("dev.vertique.examples.cron.NotAContractHandler", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import dev.vertique.services.ServiceHandler;
                                import io.vertx.core.Future;

                                public class NotAContractHandler implements ServiceHandler<NotAContract> {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    public Future<Void> tick() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("is not annotated with @ServiceContract");
    }

    @Test
    @DisplayName("ServiceHandler<C> with extra @ServiceContract interface(s) -> error")
    void serviceHandlerWithAdditionalContracts_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.secondContract(),
                        SourceFiles.inline("dev.vertique.examples.cron.HandlerPlusExtra", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import dev.vertique.services.ServiceHandler;
                                import io.vertx.core.Future;

                                public class HandlerPlusExtra implements ServiceHandler<OrderContract>, AnotherContract {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    public Future<Void> closeStaleOrders() { return Future.succeededFuture(); }

                                    @Override
                                    public Future<Void> doIt() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("implements additional @ServiceContract interface(s)");
    }

    @Test
    @DisplayName("@CronJob on an abstract superclass method -> error (runtime scans declared methods only)")
    void cronJobOnAbstractSuperclass_isError() {
        // Mirror runtime CronJobRegistrar.java:168: implClass.getDeclaredMethods() is non-recursive.
        // A @CronJob declared on an abstract base class method is silently dropped at runtime;
        // reject it at compile time so the validator catches what the runtime won't run.
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        SourceFiles.inline("dev.vertique.examples.cron.AbstractBaseService", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import io.vertx.core.Future;

                                public abstract class AbstractBaseService implements OrderContract {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    @Override
                                    public Future<Void> closeStaleOrders() { return Future.succeededFuture(); }
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.cron.ConcreteService", """
                                package dev.vertique.examples.cron;

                                public class ConcreteService extends AbstractBaseService {}
                                """))
                .assertFailed()
                .assertErrorMessage("@CronJob must be declared on a concrete service implementation");
    }

    @Test
    @DisplayName("impl method with no name match on contract -> error")
    void noNameMatchOnContract_isError() {
        // OrderContract declares closeStaleOrders(); the impl declares a @CronJob method with a
        // different name (renamedTick) — which is NOT @Override and has no contract counterpart.
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        SourceFiles.inline("dev.vertique.examples.cron.MismatchService", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import io.vertx.core.Future;

                                public class MismatchService implements OrderContract {
                                    @CronJob(id = "x", cron = "0 0 * * * *")
                                    public Future<Void> renamedTick() { return Future.succeededFuture(); }

                                    @Override
                                    public Future<Void> closeStaleOrders() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("has no matching method named");
    }
}
