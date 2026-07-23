// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;

/**
 * Shared inline source fixtures for {@code CronJobProcessor} tests. Each helper returns a
 * {@link JavaFileObject} with a small contract interface or impl class; tests assemble fixtures
 * by passing the helpers into {@code ProcessorTestHarness.run(...)}.
 *
 * <p>Tests reference real annotations from {@code vertique-job-cron} ({@code @CronJob}) and
 * {@code vertique-services} ({@code @ServiceContract}, {@code @ServiceOperation},
 * {@code ServiceHandler}). Both are available on the test classpath.
 */
final class CronJobProcessorFixtures {

    private CronJobProcessorFixtures() {}

    /** A {@code @ServiceContract} interface with one operation that has {@code @ServiceOperation}. */
    static JavaFileObject contractWithServiceOperation() {
        return SourceFiles.inline("dev.vertique.examples.cron.OrderContract", """
                package dev.vertique.examples.cron;

                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                @ServiceContract("order-service")
                public interface OrderContract {
                    @ServiceOperation("close-stale-orders")
                    Future<Void> closeStaleOrders();
                }
                """);
    }

    /** A {@code @ServiceContract} interface with one operation that lacks {@code @ServiceOperation}. */
    static JavaFileObject contractWithoutServiceOperation() {
        return SourceFiles.inline("dev.vertique.examples.cron.OrderContract", """
                package dev.vertique.examples.cron;

                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;

                @ServiceContract("order-service")
                public interface OrderContract {
                    Future<Void> closeStaleOrders();
                }
                """);
    }

    /** A second {@code @ServiceContract} interface used to test ambiguity rejection. */
    static JavaFileObject secondContract() {
        return SourceFiles.inline("dev.vertique.examples.cron.AnotherContract", """
                package dev.vertique.examples.cron;

                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;

                @ServiceContract("another-service")
                public interface AnotherContract {
                    Future<Void> doIt();
                }
                """);
    }

    /** Emits an impl source with the given body slot for the {@code @CronJob} method. */
    static JavaFileObject implWithCronJobBody(String cronJobAttributes, String methodSignature) {
        return SourceFiles.inline(
                "dev.vertique.examples.cron.OrderService", """
                package dev.vertique.examples.cron;

                import dev.vertique.job.cron.CronJob;
                import io.vertx.core.Future;

                public class OrderService implements OrderContract {
                    @CronJob(%s)
                    %s
                }
                """.formatted(cronJobAttributes, methodSignature));
    }

    /** Emits an impl that does not implement any {@code @ServiceContract}. */
    static JavaFileObject implWithoutContract() {
        return SourceFiles.inline("dev.vertique.examples.cron.OrphanService", """
                package dev.vertique.examples.cron;

                import dev.vertique.job.cron.CronJob;
                import io.vertx.core.Future;

                public class OrphanService {
                    @CronJob(id = "orphan", cron = "0 0 * * * *")
                    public Future<Void> tick() {
                        return Future.succeededFuture();
                    }
                }
                """);
    }

    /** Emits an impl that implements two {@code @ServiceContract} interfaces (ambiguity). */
    static JavaFileObject implWithTwoContracts() {
        return SourceFiles.inline("dev.vertique.examples.cron.AmbiguousService", """
                package dev.vertique.examples.cron;

                import dev.vertique.job.cron.CronJob;
                import io.vertx.core.Future;

                public class AmbiguousService implements OrderContract, AnotherContract {
                    @CronJob(id = "amb", cron = "0 0 * * * *")
                    @Override
                    public Future<Void> closeStaleOrders() {
                        return Future.succeededFuture();
                    }

                    @Override
                    public Future<Void> doIt() {
                        return Future.succeededFuture();
                    }
                }
                """);
    }

    /** Emits a {@code ServiceHandler<OrderContract>} impl with a matching named method. */
    static JavaFileObject handlerImpl() {
        return SourceFiles.inline("dev.vertique.examples.cron.OrderHandler", """
                package dev.vertique.examples.cron;

                import dev.vertique.job.cron.CronJob;
                import dev.vertique.services.ServiceHandler;
                import io.vertx.core.Future;

                public class OrderHandler implements ServiceHandler<OrderContract> {
                    @CronJob(id = "handler-cron", cron = "0 0 * * * *")
                    public Future<Void> closeStaleOrders() {
                        return Future.succeededFuture();
                    }
                }
                """);
    }

    /**
     * Emits an impl that uses both the handler pattern AND directly implements the contract
     * (rejected by {@code ContractDiscovery}).
     */
    static JavaFileObject handlerImplPlusDirect() {
        return SourceFiles.inline("dev.vertique.examples.cron.MixedService", """
                package dev.vertique.examples.cron;

                import dev.vertique.job.cron.CronJob;
                import dev.vertique.services.ServiceHandler;
                import io.vertx.core.Future;

                public class MixedService implements ServiceHandler<OrderContract>, OrderContract {
                    @CronJob(id = "mixed", cron = "0 0 * * * *")
                    @Override
                    public Future<Void> closeStaleOrders() {
                        return Future.succeededFuture();
                    }
                }
                """);
    }
}
