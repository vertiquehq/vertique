// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static org.mockito.Mockito.mock;

import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.services.di.WorkflowSourceModule;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger test components for the delivery bean. Each stub module provides only the collaborators
 * the named module needs, so that a component compiles only when the module asks for nothing more.
 */
final class GraphFixture {

    private GraphFixture() {}

    /** The collaborators of the source module alone: operations, pool and the context scope. */
    @Module
    abstract static class SourceStubs {
        @Provides
        @Singleton
        @SuppressWarnings("unchecked")
        static TransactionalWorkflowOperations<SqlClient> operations() {
            return mock(TransactionalWorkflowOperations.class);
        }

        @Provides
        @Singleton
        static Pool pool() {
            return mock(Pool.class);
        }

        @Provides
        @Singleton
        static InboundExecutionContextScope contextScope() {
            return DeliveryFixture.scopeOver(new DefaultContextHolder());
        }
    }

    /** What the services module needs on top of the source stubs: the outbox and inbox side. */
    @Module
    abstract static class OutboxInboxStubs {
        @Provides
        @Singleton
        static InboxService inboxService() {
            return mock(InboxService.class);
        }

        @Provides
        @Singleton
        static OutboxService outboxService() {
            return mock(OutboxService.class);
        }

        @Provides
        @Singleton
        static ServiceTargetResolver targetResolver() {
            return mock(ServiceTargetResolver.class);
        }

        @Multibinds
        abstract Set<OutboxDestinationHandler> destinationHandlers();
    }

    /** Declares the bean optional, the way the Camel module will. */
    @Module
    interface OptionalDeliveryModule {
        @BindsOptionalOf
        WorkflowSourceDelivery optionalDelivery();
    }

    /** A node that installs the services module, which includes the source module. */
    @Singleton
    @Component(modules = {WorkflowServicesModule.class, SourceStubs.class, OutboxInboxStubs.class})
    interface ServicesComponent {
        WorkflowSourceDelivery delivery();

        Set<ServiceContractContributor> contributors();

        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder vertx(Vertx vertx);

            ServicesComponent build();
        }
    }

    /** A node that only receives signals and installs the source module alone. */
    @Singleton
    @Component(modules = {WorkflowSourceModule.class, SourceStubs.class})
    interface SourceOnlyComponent {
        WorkflowSourceDelivery delivery();

        Set<SignalSourceObserver> observers();

        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder vertx(Vertx vertx);

            SourceOnlyComponent build();
        }
    }

    /** The source module plus an optional declaration for the bean. */
    @Singleton
    @Component(modules = {WorkflowSourceModule.class, SourceStubs.class, OptionalDeliveryModule.class})
    interface OptionalComponent {
        Optional<WorkflowSourceDelivery> optionalDelivery();

        WorkflowSourceDelivery delivery();

        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder vertx(Vertx vertx);

            OptionalComponent build();
        }
    }

    static ServicesComponent component(Vertx vertx) {
        return DaggerGraphFixture_ServicesComponent.builder().vertx(vertx).build();
    }

    static SourceOnlyComponent sourceOnlyComponent(Vertx vertx) {
        return DaggerGraphFixture_SourceOnlyComponent.builder().vertx(vertx).build();
    }

    static OptionalComponent optionalComponent(Vertx vertx) {
        return DaggerGraphFixture_OptionalComponent.builder().vertx(vertx).build();
    }
}
