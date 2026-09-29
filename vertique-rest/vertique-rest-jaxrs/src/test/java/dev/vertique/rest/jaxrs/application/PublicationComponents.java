// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.publication.fixture.CountingSchemaSource;
import dev.vertique.rest.jaxrs.publication.fixture.DuplicateOperationIdResource;
import dev.vertique.rest.jaxrs.publication.fixture.FailingFutureSink;
import dev.vertique.rest.jaxrs.publication.fixture.MgmtResource;
import dev.vertique.rest.jaxrs.publication.fixture.MountPathRecordingCustomizer;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecorder;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecordingCustomizer;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecordingHook;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecordingSink;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingSink;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingValidationStrategy;
import dev.vertique.rest.jaxrs.publication.fixture.ThrowingSink;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link OperationPublicationDeploymentIT}: composition (a) (zero
 * declarations: the default legacy mount, a hand-built {@code /api/mgmt/*} mount, a hand-built
 * empty {@code /api/empty/*} mount, and one non-JAX-RS mount) and composition (b) (T023's ported
 * {@code unitb} applications, exactly the module set {@link DeploymentComponents.StandardComponent}
 * uses), each built several ways for TP-001, TP-006, and TP-007 by swapping in one of the small
 * nested sink/strategy modules below.
 */
public final class PublicationComponents {

    private PublicationComponents() {}

    /** Provisions every component here exposes: a fresh {@link HttpVerticle} per call. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle}.
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();
    }

    // --- Nested modules: hand-built mounts (composition (a)) ---

    /**
     * Composition (a)'s hand-built mounts: {@code /api/mgmt/*} with {@link MgmtResource} as its
     * sole resource, an empty {@code /api/empty/*} mount, and one non-JAX-RS mount.
     */
    @Module
    static final class HandBuiltMountsModule {

        /** Composition (a)'s hand-built {@code /api/mgmt/*} mount path. */
        static final String MGMT_MOUNT_PATH = "/api/mgmt/*";

        /** Composition (a)'s hand-built empty mount path. */
        static final String EMPTY_MOUNT_PATH = "/api/empty/*";

        @Provides
        @IntoSet
        static RouterMount mgmtMount(JaxRsRouterMount.Factory factory) {
            return factory.create(MGMT_MOUNT_PATH, "openapi.json", Set.of(new MgmtResource()));
        }

        @Provides
        @IntoSet
        static RouterMount emptyMount(JaxRsRouterMount.Factory factory) {
            return factory.create(EMPTY_MOUNT_PATH, "openapi.json", Set.of());
        }

        @Provides
        @IntoSet
        static RouterMount plainMount() {
            return new PlainRouterMount();
        }
    }

    /**
     * TP-006(b)'s variant of {@link HandBuiltMountsModule}: the {@code /api/mgmt/*} resource set
     * also contains {@link DuplicateOperationIdResource}, which shares {@link MgmtResource#echo()}'s
     * operation id.
     */
    @Module
    static final class HandBuiltMountsWithDuplicateModule {

        @Provides
        @IntoSet
        static RouterMount mgmtMount(JaxRsRouterMount.Factory factory) {
            return factory.create(
                    HandBuiltMountsModule.MGMT_MOUNT_PATH,
                    "openapi.json",
                    Set.of(new MgmtResource(), new DuplicateOperationIdResource()));
        }

        @Provides
        @IntoSet
        static RouterMount emptyMount(JaxRsRouterMount.Factory factory) {
            return factory.create(HandBuiltMountsModule.EMPTY_MOUNT_PATH, "openapi.json", Set.of());
        }

        @Provides
        @IntoSet
        static RouterMount plainMount() {
            return new PlainRouterMount();
        }
    }

    /**
     * Composition (a)'s one non-JAX-RS {@link RouterMount}: two fixed-body {@code GET} endpoints,
     * used by TP-007's "success on every mount" requests.
     */
    private static final class PlainRouterMount implements RouterMount {

        /** This mount's path. */
        static final String MOUNT_PATH = "/plain/*";

        @Override
        public String mountPath() {
            return MOUNT_PATH;
        }

        @Override
        public Future<Router> createRouter(Vertx vertx) {
            Router router = Router.router(vertx);
            router.get("/status").handler(ctx -> ctx.response()
                    .putHeader("Content-Type", "text/plain")
                    .end("status"));
            router.get("/health").handler(ctx -> ctx.response()
                    .putHeader("Content-Type", "text/plain")
                    .end("health"));
            return Future.succeededFuture(router);
        }
    }

    // --- Nested modules: TP-001's event-recording fixtures ---

    /**
     * TP-001's recording sink, counting {@link RouterLifecycleHook}, and counting
     * {@link MountCustomizer}, all sharing one {@link PublicationEventRecorder}.
     */
    @Module
    static final class PublicationEventFixturesModule {

        @Provides
        @Singleton
        static PublicationEventRecorder publicationEventRecorder() {
            return new PublicationEventRecorder();
        }

        @Provides
        @Singleton
        static PublicationEventRecordingSink publicationEventRecordingSink(PublicationEventRecorder recorder) {
            return new PublicationEventRecordingSink(recorder);
        }

        @Provides
        @IntoSet
        static OperationPublicationSink asOperationPublicationSink(PublicationEventRecordingSink sink) {
            return sink;
        }

        @Provides
        @IntoSet
        static RouterLifecycleHook asRouterLifecycleHook(PublicationEventRecorder recorder) {
            return new PublicationEventRecordingHook(recorder);
        }

        @Provides
        @IntoSet
        static MountCustomizer asMountCustomizer(PublicationEventRecorder recorder) {
            return new PublicationEventRecordingCustomizer(recorder);
        }
    }

    // --- Nested modules: TP-006's sinks and counting customizer ---

    /** TP-006(a)'s sink: throws for {@link HandBuiltMountsModule#MGMT_MOUNT_PATH}. */
    @Module
    static final class ThrowingSinkModule {

        @Provides
        @Singleton
        static ThrowingSink throwingSink(RestConfigurationException sinkFailure) {
            return new ThrowingSink(HandBuiltMountsModule.MGMT_MOUNT_PATH, sinkFailure);
        }

        @Provides
        @IntoSet
        static OperationPublicationSink asSink(ThrowingSink sink) {
            return sink;
        }
    }

    /** TP-006(c)'s sink: fails its future for {@link HandBuiltMountsModule#MGMT_MOUNT_PATH}. */
    @Module
    static final class FailingFutureSinkModule {

        @Provides
        @Singleton
        static FailingFutureSink failingFutureSink(RestConfigurationException sinkFailure) {
            return new FailingFutureSink(HandBuiltMountsModule.MGMT_MOUNT_PATH, sinkFailure);
        }

        @Provides
        @IntoSet
        static OperationPublicationSink asSink(FailingFutureSink sink) {
            return sink;
        }
    }

    /** TP-006's counting {@link MountCustomizer}. */
    @Module
    static final class MountPathCustomizerModule {

        @Provides
        @Singleton
        static MountPathRecordingCustomizer mountPathRecordingCustomizer() {
            return new MountPathRecordingCustomizer();
        }

        @Provides
        @IntoSet
        static MountCustomizer asMountCustomizer(MountPathRecordingCustomizer customizer) {
            return customizer;
        }
    }

    // --- Nested modules: plain recording sinks (TP-006(b) guard, TP-007) ---

    /** A {@link RecordingSink} that never wants detail. */
    @Module
    static final class RecordingSinkModule {

        @Provides
        @Singleton
        static RecordingSink recordingSink() {
            return new RecordingSink();
        }

        @Provides
        @IntoSet
        static OperationPublicationSink asSink(RecordingSink sink) {
            return sink;
        }
    }

    /** A {@link RecordingSink} that wants detail for every mount. */
    @Module
    static final class RecordingSinkWantsAllDetailModule {

        @Provides
        @Singleton
        static RecordingSink recordingSink() {
            return new RecordingSink(applicationName -> true);
        }

        @Provides
        @IntoSet
        static OperationPublicationSink asSink(RecordingSink sink) {
            return sink;
        }
    }

    // --- Nested module: TP-007's counting schema source and recording strategy ---

    /**
     * TP-007's counting {@link OperationSchemaSource} (schema content is irrelevant to TP-007,
     * which compares only call counts and response tables) and the {@link RecordingValidationStrategy}
     * selected by {@code jaxrs.validationStrategy=recording-strategy}.
     */
    @Module
    static final class CountingSourceAndRecordingStrategyModule {

        @Provides
        @Singleton
        static CountingSchemaSource countingSchemaSource() {
            return new CountingSchemaSource(
                    (descriptor, call) -> OperationSchemas.builder().build());
        }

        @Provides
        static OperationSchemaSource operationSchemaSource(CountingSchemaSource source) {
            return source;
        }

        @Provides
        @Singleton
        static RecordingValidationStrategy recordingValidationStrategy() {
            return new RecordingValidationStrategy();
        }

        @Provides
        @IntoSet
        static RequestValidationStrategy asStrategy(RecordingValidationStrategy strategy) {
            return strategy;
        }
    }

    // --- Components: TP-001 ---

    /** TP-001 composition (a): zero declarations, plus the event-recording fixtures. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                PublicationEventFixturesModule.class
            })
    public interface ZeroDeclarationEventsComponent extends Provisions {

        /**
         * Resolves the shared event recorder.
         *
         * @return the event recorder
         */
        PublicationEventRecorder eventRecorder();

        /**
         * Resolves the recording sink.
         *
         * @return the recording sink
         */
        PublicationEventRecordingSink eventRecordingSink();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ZeroDeclarationEventsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-001 composition (b): the same module set {@link DeploymentComponents.StandardComponent}
     * uses, plus the event-recording fixtures.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.manual.ManualResourceModule.class,
                PublicationEventFixturesModule.class
            })
    public interface UnitBApplicationsEventsComponent extends Provisions {

        /**
         * Resolves the shared event recorder.
         *
         * @return the event recorder
         */
        PublicationEventRecorder eventRecorder();

        /**
         * Resolves the recording sink.
         *
         * @return the recording sink
         */
        PublicationEventRecordingSink eventRecordingSink();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            UnitBApplicationsEventsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    // --- Components: TP-006 ---

    /** TP-006(a): composition (a) with a sink that throws for the mgmt mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                ThrowingSinkModule.class,
                MountPathCustomizerModule.class
            })
    public interface ThrowingSinkComponent extends Provisions {

        /**
         * Resolves the throwing sink.
         *
         * @return the throwing sink
         */
        ThrowingSink throwingSink();

        /**
         * Resolves the counting customizer.
         *
         * @return the counting customizer
         */
        MountPathRecordingCustomizer customizerRecorder();

        /** Factory taking the application configuration and the sink's thrown exception. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration and failure.
             *
             * @param config      the application configuration
             * @param sinkFailure the exception {@link ThrowingSink} throws for the mgmt mount
             * @return the constructed component
             */
            ThrowingSinkComponent create(
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance RestConfigurationException sinkFailure);
        }
    }

    /** TP-006(b) guard: composition (a) with a duplicate operationId on the mgmt mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsWithDuplicateModule.class,
                RecordingSinkModule.class,
                MountPathCustomizerModule.class
            })
    public interface DuplicateOperationComponent extends Provisions {

        /**
         * Resolves the recording sink.
         *
         * @return the recording sink
         */
        RecordingSink recordingSink();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DuplicateOperationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** TP-006(c): composition (a) with a sink whose future fails for the mgmt mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                FailingFutureSinkModule.class,
                MountPathCustomizerModule.class
            })
    public interface FailingFutureSinkComponent extends Provisions {

        /**
         * Resolves the failing-future sink.
         *
         * @return the failing-future sink
         */
        FailingFutureSink failingFutureSink();

        /**
         * Resolves the counting customizer.
         *
         * @return the counting customizer
         */
        MountPathRecordingCustomizer customizerRecorder();

        /** Factory taking the application configuration and the sink future's failure. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration and failure.
             *
             * @param config      the application configuration
             * @param sinkFailure the failure {@link FailingFutureSink}'s future carries for the mgmt mount
             * @return the constructed component
             */
            FailingFutureSinkComponent create(
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance RestConfigurationException sinkFailure);
        }
    }

    // --- Components: TP-007 ---

    /** TP-007 build 1: composition (a) with the {@code @Multibinds} sink set empty. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                CountingSourceAndRecordingStrategyModule.class
            })
    public interface NoSinkComponent extends Provisions {

        /**
         * Resolves the counting schema source.
         *
         * @return the counting schema source
         */
        CountingSchemaSource countingSchemaSource();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            NoSinkComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** TP-007 build 2: composition (a) with a sink that wants no detail. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                CountingSourceAndRecordingStrategyModule.class,
                RecordingSinkModule.class
            })
    public interface SinkWantsNoDetailComponent extends Provisions {

        /**
         * Resolves the counting schema source.
         *
         * @return the counting schema source
         */
        CountingSchemaSource countingSchemaSource();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SinkWantsNoDetailComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** TP-007 build 3: composition (a) with a sink that wants detail for every mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.legacy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.legacy.ManualResourceModule.class,
                HandBuiltMountsModule.class,
                CountingSourceAndRecordingStrategyModule.class,
                RecordingSinkWantsAllDetailModule.class
            })
    public interface SinkWantsAllDetailComponent extends Provisions {

        /**
         * Resolves the counting schema source.
         *
         * @return the counting schema source
         */
        CountingSchemaSource countingSchemaSource();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SinkWantsAllDetailComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
