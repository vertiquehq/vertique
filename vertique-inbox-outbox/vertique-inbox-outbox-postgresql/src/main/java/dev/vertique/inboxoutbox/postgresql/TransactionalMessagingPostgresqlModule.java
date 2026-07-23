// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.TransactionalMessagingModule;
import dev.vertique.inboxoutbox.postgresql.compose.InboxOutboxPostgresqlComposeValidator;
import dev.vertique.inboxoutbox.postgresql.maintenance.OutboxMaintenanceServiceImpl;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.services.Services;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dagger module that wires the PostgreSQL-backed transactional messaging implementation.
 *
 * <p>Binds both repository interfaces to {@link PgInboxOutboxRepository}, registers the service
 * implementations, and contributes a set of {@link VerticleDeployment} descriptors for the
 * {@link OutboxRelay} verticle.
 *
 * <p>Includes {@link TransactionalMessagingModule} so that the {@link OutboxDestinationHandler}
 * multibinding is available for adapter modules to contribute to. Each registered handler declares
 * its own {@link ClaimScope} via {@link OutboxDestinationHandler#claimScope()}, which is collected
 * at startup to build the {@link RelayCapabilities} that drive the dynamic claim query.
 *
 * <p>Usage:
 * <pre>{@code
 * @Component(modules = {
 *     DbPostgresqlModule.class,
 *     TransactionalMessagingPostgresqlModule.class,
 *     // optional adapter modules:
 *     // InboxOutboxServicesModule.class,
 *     // InboxOutboxKafkaModule.class,
 * })
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module(
        includes = {
            TransactionalMessagingModule.class,
            dev.vertique.context.ContextRuntimeModule.class,
            LoggingContextModule.class
        })
public abstract class TransactionalMessagingPostgresqlModule {

    /**
     * Provides the {@link InboxRepository} binding, delegating to the shared PostgreSQL
     * repository implementation.
     *
     * @param impl the PostgreSQL inbox/outbox repository
     * @return the {@link InboxRepository} to use throughout the application
     */
    @Provides
    @Singleton
    static InboxRepository inboxRepository(PgInboxOutboxRepository impl) {
        return impl;
    }

    /**
     * Provides the {@link OutboxRepository} binding, delegating to the shared PostgreSQL
     * repository implementation.
     *
     * @param impl the PostgreSQL inbox/outbox repository
     * @return the {@link OutboxRepository} to use throughout the application
     */
    @Provides
    @Singleton
    static OutboxRepository outboxRepository(PgInboxOutboxRepository impl) {
        return impl;
    }

    /**
     * Provides the {@link InboxService} binding, delegating to the default implementation.
     *
     * @param impl the default inbox service
     * @return the {@link InboxService} to use throughout the application
     */
    @Provides
    @Singleton
    static InboxService inboxService(DefaultInboxService impl) {
        return impl;
    }

    /**
     * Provides the {@link OutboxService} binding, delegating to the default implementation.
     *
     * @param impl the default outbox service
     * @return the {@link OutboxService} to use throughout the application
     */
    @Provides
    @Singleton
    static OutboxService outboxService(DefaultOutboxService impl) {
        return impl;
    }

    /**
     * Provides {@link OutboxRelayConfig} as a Dagger {@link Singleton} binding so it can be
     * injected by both {@link OutboxRelay} and the cluster-singleton outbox maintenance service.
     *
     * <p>Deserialised from the {@code inboxOutbox.relay} section of the application config.
     *
     * @param config the application configuration root
     * @param parser the injected config parser
     * @return the outbox relay configuration
     */
    @Provides
    @Singleton
    static OutboxRelayConfig outboxRelayConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(JsonConfigPaths.navigateObject(config, "inboxOutbox"), "relay"),
                OutboxRelayConfig.class);
    }

    /**
     * Contributes {@link OutboxMaintenanceServiceImpl} into the {@code @Services} multibinding so
     * the cron registrar discovers its {@code @CronJob}-annotated maintenance methods
     * ({@code recoverStaleLeases} and {@code cleanup}).
     *
     * @param impl the outbox maintenance service implementation
     * @return the impl as a raw {@link Object} (required by the {@code @Services} binding shape)
     */
    @Provides
    @Singleton
    @IntoSet
    @Services
    static Object outboxMaintenance(OutboxMaintenanceServiceImpl impl) {
        return impl;
    }

    /**
     * Provides {@link InboxOutboxCleanupConfig} as a Dagger {@link Singleton} binding so it can be
     * injected by both {@link OutboxRelay} and the cluster-singleton outbox maintenance service.
     *
     * <p>Deserialised from the {@code inboxOutbox.cleanup} section of the application config.
     *
     * @param config the application configuration root
     * @param parser the injected config parser
     * @return the inbox/outbox cleanup configuration
     */
    @Provides
    @Singleton
    static InboxOutboxCleanupConfig inboxOutboxCleanupConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(JsonConfigPaths.navigateObject(config, "inboxOutbox"), "cleanup"),
                InboxOutboxCleanupConfig.class);
    }

    /**
     * Contributes {@link InboxOutboxPostgresqlComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing its construction-time,
     * constructible-as-validation check) with no app code referencing it directly.
     *
     * <p>Dagger constructs the validator through its {@code @Inject} constructor, which
     * establishes the required dependency edges ({@link dev.vertique.job.cron.CronJobRegistrar},
     * {@link dev.vertique.job.cron.CronScheduler},
     * {@link dev.vertique.job.cron.dagger.CronPersistenceMarker}).
     *
     * @param impl the compose validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator inboxOutboxPostgresqlComposeValidator(InboxOutboxPostgresqlComposeValidator impl) {
        return impl;
    }

    /**
     * Provides the set of {@link VerticleDeployment} descriptors for outbox relay instances.
     *
     * <p>Reads relay configuration from {@code inboxOutbox.relay} in the application config.
     * Deploys {@link OutboxRelayConfig#instances()} instances of {@link OutboxRelay} in the
     * {@link LifecyclePhase#SERVICES} phase at priority 100.
     *
     * <p>The relay deployment uses the {@code nodeId} derived from the shared
     * {@link PgInboxOutboxRepository} instance as a base prefix, then appends a per-instance
     * counter suffix (e.g., {@code "hostname-a1b2c3d4-0"}, {@code "hostname-a1b2c3d4-1"}) so
     * that each {@link OutboxRelay} instance holds a unique {@code claimed_by} value and stale
     * lease reclaim operates correctly under multi-instance deployments.
     *
     * <p>{@link RelayCapabilities} are derived directly from the deduped handler map: each
     * registered {@link OutboxDestinationHandler} must return a non-null {@link ClaimScope} from
     * {@link OutboxDestinationHandler#claimScope()}. A {@code null} scope fails fast at startup
     * with a {@link NullPointerException} naming the offending destination type
     * (see {@link OutboxRelay#deriveCapabilities(Map)}).
     *
     * @param relayConfig      outbox relay configuration (polling interval, batch size, etc.)
     * @param pgRepository     the concrete PostgreSQL repository (provides nodeId)
     * @param handlers         the set of destination handlers contributed by adapter modules
     * @param connectOptions   PostgreSQL connect options for the LISTEN/NOTIFY connection
     * @param composeValidator compose-time validation guard; its presence as a parameter forces
     *                         Dagger to construct the validator (and the cron bindings it depends
     *                         on) during graph construction, providing the manual-path guarantee
     *                         until Phase 5 migrates examples off manual choreography
     * @return a singleton set containing the relay verticle deployment descriptor
     */
    @Provides
    @Singleton
    @ElementsIntoSet
    static Set<VerticleDeployment> relayDeployments(
            OutboxRelayConfig relayConfig,
            PgInboxOutboxRepository pgRepository,
            Set<OutboxDestinationHandler> handlers,
            PgConnectOptions connectOptions,
            @SuppressWarnings("unused") InboxOutboxPostgresqlComposeValidator composeValidator) {
        Map<DestinationType, OutboxDestinationHandler> handlerMap = OutboxRelay.buildHandlerMap(handlers);
        RelayCapabilities capabilities = OutboxRelay.deriveCapabilities(handlerMap);

        String baseNodeId = pgRepository.nodeId();
        AtomicInteger instanceCounter = new AtomicInteger();

        return Set.of(new VerticleDeployment(
                "outbox-relay",
                () -> new OutboxRelay(
                        relayConfig,
                        pgRepository,
                        handlerMap,
                        capabilities,
                        connectOptions,
                        baseNodeId + "-" + instanceCounter.getAndIncrement()),
                new DeploymentOptions().setInstances(relayConfig.instances()),
                LifecyclePhase.SERVICES,
                100));
    }
}
