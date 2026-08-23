// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.micrometer.MicrometerModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Framework wiring for {@link McpMetricsCompositionTest} (T021 composition proof, review
 * remediation finding #6): builds the omitting graph ({@link McpMicrometerModule} absent from its
 * module list) and the installed graph — {@link McpMicrometerModule} alongside the real {@link
 * MicrometerModule}, exercising both modules' {@code @BindsOptionalOf MetricsConfig} declarations
 * together in one composed graph. Nothing decisive lives here.
 */
final class McpMetricsCompositionTestFixture {

    private McpMetricsCompositionTestFixture() {}

    /** Builds the omitting graph: {@link McpMicrometerModule} is absent from this component's module list. */
    static OmittingComponent buildOmittingGraph() {
        return DaggerMcpMetricsCompositionTestFixture_OmittingComponent.create();
    }

    /**
     * Builds the installed graph: {@link McpMicrometerModule} composed alongside the real {@link
     * MicrometerModule} — the same co-installation an application performs (see {@link
     * McpMicrometerModule}'s class Javadoc).
     */
    static InstalledComponent buildInstalledGraph() {
        return DaggerMcpMetricsCompositionTestFixture_InstalledComponent.builder()
                .testConfigModule(new TestConfigModule(new JsonObject()))
                .build();
    }

    // --- Dagger composition ---

    /**
     * The omitting graph: {@link McpMicrometerModule} is absent from this list by construction —
     * this component's source carries no import of, or reference to, any {@code
     * dev.vertique.mcp.micrometer} type other than this file itself, {@link
     * McpMetricsCompositionTest}, and {@link McpMicrometerModule}'s own name here in Javadoc — so it
     * structurally cannot contribute {@link McpServerMetricsObserver}.
     */
    @Singleton
    @Component(modules = {OmittingModule.class})
    interface OmittingComponent {
        Set<McpRequestLifecycleObserver> lifecycleObservers();
    }

    /** Declares the empty {@code Set<McpRequestLifecycleObserver>} fallback for the omitting graph. */
    @Module
    abstract static class OmittingModule {
        private OmittingModule() {}

        @Multibinds
        abstract Set<McpRequestLifecycleObserver> lifecycleObservers();
    }

    /** The installed graph: {@link McpMicrometerModule} composed alongside the real {@link MicrometerModule}. */
    @Singleton
    @Component(
            modules = {
                McpMicrometerModule.class,
                MicrometerModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface InstalledComponent {
        Set<McpRequestLifecycleObserver> lifecycleObservers();

        MeterRegistry meterRegistry();

        @Component.Builder
        interface Builder {
            Builder testConfigModule(TestConfigModule testConfigModule);

            InstalledComponent build();
        }
    }

    /** Provides the {@code @VertxConfig JsonObject} the real {@link MicrometerModule} requires. */
    @Module
    static final class TestConfigModule {
        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
