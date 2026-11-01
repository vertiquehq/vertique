// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.examples.workflow.load.workflow.LoadDefinition;
import dev.vertique.examples.workflow.load.workflow.LoadWorkflow;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.registry.WorkflowContributor;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Application bindings of a load node: the harness settings, the workload definition, the dispatch handler, the
 * loopback ingress and the startup validation.
 *
 * <p>The ingress deploys in the {@link LifecyclePhase#EDGE} phase, after the services, the executor and the relay,
 * so a node that answers on it has finished starting.
 */
@Module
public abstract class LoadNodeModule {

    /**
     * Parses the {@code load} section, rejecting unknown keys.
     *
     * @param config the application config
     * @param parser the canonical config parser
     * @return the harness settings
     */
    @Provides
    @Singleton
    static LoadConfig loadConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return LoadConfig.parse(JsonConfigPaths.navigateObject(config, "load"), parser);
    }

    /**
     * Provides the system UTC clock the workflow engine uses for timestamps.
     *
     * @return the system UTC clock
     */
    @Provides
    @Singleton
    static Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Provides the typed client of the load workflow, built from its contract and definition id.
     *
     * <p>The client is not built from the definition class, because a node that runs no executor registers no
     * definition class and the ingress must also work there.
     *
     * @param factory the workflow client factory
     * @return the client
     */
    @Provides
    @Singleton
    static LoadWorkflow loadWorkflow(WorkflowClientFactory factory) {
        return factory.create(LoadWorkflow.class, LoadDefinition.DEFINITION_ID);
    }

    /**
     * Registers the load definition with the workflow registry.
     *
     * @param definition the definition
     * @return the contributor
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowContributor loadDefinitionContributor(LoadDefinition definition) {
        return registry -> registry.register(definition);
    }

    /**
     * Registers the dispatch handler with the services framework.
     *
     * @param contributor the contributor of the {@code load.work} operation
     * @return the contributor
     */
    @Provides
    @Singleton
    @IntoSet
    static ServiceContractContributor loadServiceContributor(LoadServiceContributor contributor) {
        return contributor;
    }

    /**
     * Contributes the loopback ingress deployment.
     *
     * @param provider lazy provider of the ingress verticle
     * @return the deployment in the {@link LifecyclePhase#EDGE} phase
     */
    @Provides
    @IntoSet
    static VerticleDeployment ingressDeployment(Provider<IngressVerticle> provider) {
        return VerticleDeployment.of("load-ingress", provider::get, LifecyclePhase.EDGE);
    }

    /**
     * Contributes the startup validation of a load node.
     *
     * @param validator the validator; its construction performs the checks
     * @return the validator
     */
    @Provides
    @IntoSet
    static ComposeValidator loadNodeValidator(LoadNodeValidator validator) {
        return validator;
    }
}
