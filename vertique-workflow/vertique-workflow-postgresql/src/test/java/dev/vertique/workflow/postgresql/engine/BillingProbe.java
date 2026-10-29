// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.postgresql.engine.BillingService.ChargeRequest;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A downstream service operation, {@code billing.charge}, that records the principal it runs as. The relay delivers a
 * workflow's dispatch to it, so what it records is the principal the downstream operation sees.
 */
@Singleton
public final class BillingProbe implements BillingService {

    private static final Method CHARGE;

    static {
        try {
            CHARGE = BillingService.class.getMethod("charge", ChargeRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ContextHolder holder;
    private final WorkflowActorMapper mapper = new DefaultWorkflowActorMapper();
    private final List<WorkflowActor> principals = new CopyOnWriteArrayList<>();
    private final List<String> unauthenticated = new CopyOnWriteArrayList<>();

    /**
     * Creates the probe.
     *
     * @param holder the holder the downstream operation reads its security context from
     */
    @Inject
    public BillingProbe(ContextHolder holder) {
        this.holder = holder;
    }

    @Override
    public Future<Void> charge(ChargeRequest request) {
        holder.current(SecurityContext.class)
                .ifPresentOrElse(
                        context -> principals.add(mapper.toWorkflowActor(context.identity())),
                        () -> unauthenticated.add(request.ref()));
        return Future.succeededFuture();
    }

    /**
     * The principals the operation ran as, in call order.
     *
     * @return the principals
     */
    public List<WorkflowActor> principals() {
        return List.copyOf(principals);
    }

    /**
     * The requests the operation served with no security context at all.
     *
     * @return the refs of those requests
     */
    public List<String> withoutContext() {
        return List.copyOf(unauthenticated);
    }

    /** Registers {@code billing.charge}. */
    @Singleton
    public static final class Contributor implements ServiceContractContributor {
        private final BillingProbe probe;

        /**
         * Creates the contributor.
         *
         * @param probe the service instance
         */
        @Inject
        public Contributor(BillingProbe probe) {
            this.probe = probe;
        }

        @Override
        public List<ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config) {
            return List.of(ServiceContractEntries.deployable()
                    .contract(BillingService.class)
                    .serviceInstance(probe)
                    .name("billing")
                    .operation("charge")
                    .method(CHARGE)
                    .payloadType(ChargeRequest.class)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, ChargeRequest.class)
                    .done()
                    .deploymentOptions(config, "services", "billing")
                    .build());
        }
    }
}
