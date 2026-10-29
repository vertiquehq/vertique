// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The schema producer of the workflow schema module over the default JSON profile, for tests that build an engine
 * without an application graph and need the definition rows it selects a start from to carry a start schema.
 */
final class PlanSchemas {

    private static final WorkflowPlanSchemaProducer PRODUCER =
            DaggerPlanSchemas_Graph.create().producer();

    private PlanSchemas() {}

    /**
     * The producer.
     *
     * @return the producer of the default JSON profile
     */
    static WorkflowPlanSchemaProducer producer() {
        return PRODUCER;
    }

    @Module
    abstract static class Bindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }

        @Provides
        @Singleton
        static WorkflowTypeNames typeNames() {
            return new DefaultWorkflowTypeNames();
        }
    }

    @Singleton
    @Component(modules = {WorkflowSchemaModule.class, Bindings.class})
    interface Graph {

        WorkflowPlanSchemaProducer producer();
    }
}
