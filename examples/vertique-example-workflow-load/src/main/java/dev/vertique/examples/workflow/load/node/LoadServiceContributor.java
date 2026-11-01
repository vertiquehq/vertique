// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.examples.workflow.load.workflow.LoadWork;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Registers the dispatch handler as the service operation {@code load.work}, the target the workload's dispatch step
 * names.
 *
 * <p>The operation takes one payload, the {@link LoadWork} command, and the relay's delivery context, from which
 * the handler reads the id of the dispatching instance. Its deployment options come from the {@code services.load}
 * configuration subtree.
 */
@Singleton
public final class LoadServiceContributor implements ServiceContractContributor {

    private static final Method WORK = resolveWork();

    private final LoadServiceHandler handler;

    /**
     * Creates the contributor.
     *
     * @param handler the dispatch handler this node registers
     */
    @Inject
    public LoadServiceContributor(LoadServiceHandler handler) {
        this.handler = handler;
    }

    /**
     * Returns the contract entry of the {@code load.work} operation.
     *
     * @param config root application configuration; deployment options are read from it
     * @return a single entry
     */
    @Override
    public List<ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config) {
        return List.of(ServiceContractEntries.deployable()
                .contract(LoadServiceHandler.class)
                .serviceInstance(handler)
                .name("load")
                .operation("work")
                .method(WORK)
                .payloadType(LoadWork.class)
                .returnType(Void.class)
                .param("payload", ParamSource.PAYLOAD, LoadWork.class)
                .param("context", ParamSource.DISPATCH_CONTEXT, TransactionalMessageContext.class)
                .done()
                .deploymentOptions(config, "services", "load")
                .build());
    }

    private static Method resolveWork() {
        try {
            return LoadServiceHandler.class.getMethod("work", LoadWork.class, TransactionalMessageContext.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
