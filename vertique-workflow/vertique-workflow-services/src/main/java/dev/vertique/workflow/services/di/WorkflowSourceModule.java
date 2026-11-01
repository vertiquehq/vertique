// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.services.source.WorkflowSourceDelivery;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that provides the in-process delivery bean of a correlated signal source and the set
 * of its observers.
 *
 * <p>The module needs {@code TransactionalWorkflowOperations<SqlClient>}, {@link Pool}, {@link Vertx}
 * and {@link InboundExecutionContextScope}, and no outbox or inbox binding, so a node that only runs a
 * signal source installs this module alone. {@code WorkflowServicesModule} includes it.
 *
 * <p>The module adds no service operation and no event-bus address: only code in the application's JVM
 * can call the bean.
 */
@Module
public abstract class WorkflowSourceModule {

    /**
     * Declares the observer set, so an application with no observer still resolves the delivery bean.
     * Contribute an observer with {@code @IntoSet}.
     *
     * @return the observers that are told about every outcome
     */
    @Multibinds
    abstract Set<SignalSourceObserver> signalSourceObservers();

    /**
     * Provides the delivery bean.
     *
     * @param operations the transactional workflow operations
     * @param pool the pool whose transaction a recording runs in
     * @param vertx the Vert.x instance
     * @param contextScope installs the envelope's values on a delivery's context
     * @param observers the registered observers
     * @return the delivery bean
     */
    @Provides
    @Singleton
    static WorkflowSourceDelivery sourceDelivery(
            Provider<TransactionalWorkflowOperations<SqlClient>> operations,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope contextScope,
            Set<SignalSourceObserver> observers) {
        return new WorkflowSourceDelivery(operations, pool, vertx, contextScope, observers);
    }
}
