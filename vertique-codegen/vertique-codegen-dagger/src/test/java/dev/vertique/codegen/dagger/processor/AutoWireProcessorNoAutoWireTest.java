// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@code @NoAutoWire} correctly suppresses generation for marker types still owned by
 * {@link AutoWireProcessor}.
 *
 * <p>JAX-RS {@code @Path} resource binding moved to {@code JaxRsPipelineProcessor} in
 * {@code vertique-codegen-jaxrs} (CG-010); JAX-RS-specific {@code @NoAutoWire} coverage lives in
 * {@code JaxRsPipelineProcessorTest}. This test focuses on the markers that remain CG-002's
 * responsibility (REST client, Kafka, DelayedJob).
 */
class AutoWireProcessorNoAutoWireTest {

    private static final String NO_AUTO_WIRE_SOURCE = """
            package dev.vertique.codegen;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            @Documented
            public @interface NoAutoWire {}
            """;

    @Test
    @DisplayName(
            "migration contract: @NoAutoWire + manual @Provides @Singleton REST client survives real Dagger compilation")
    void noAutoWire_withManualRestClientProvider_compilesUnderDagger() {
        var result = ProcessorTestHarness.run(
                java.util.List.of(new AutoWireProcessor(), new dagger.internal.codegen.ComponentProcessor()),
                SourceFiles.inline("dev.vertique.codegen.NoAutoWire", NO_AUTO_WIRE_SOURCE),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                        package dev.vertique.examples.client;

                        import dev.vertique.rest.client.RestClient;
                        import dev.vertique.codegen.NoAutoWire;

                        @NoAutoWire
                        @RestClient
                        public interface UserClient {}
                        """),
                SourceFiles.inline("dev.vertique.examples.client.ClientModule", """
                        package dev.vertique.examples.client;

                        import dagger.Module;
                        import dagger.Provides;
                        import jakarta.inject.Singleton;

                        @Module
                        public abstract class ClientModule {
                            @Provides @Singleton
                            static UserClient userClient() {
                                return new UserClient() {};
                            }
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.AppComponent", """
                        package dev.vertique.examples;

                        import dagger.Component;
                        import dev.vertique.examples.client.ClientModule;
                        import dev.vertique.examples.client.UserClient;
                        import jakarta.inject.Singleton;

                        @Singleton
                        @Component(modules = ClientModule.class)
                        public interface AppComponent {
                            UserClient userClient();
                        }
                        """));

        result.assertSuccess();
        Assertions.assertTrue(
                result.compilation()
                        .generatedSourceFile("dev.vertique.examples.client.GeneratedRestClientsModule")
                        .isEmpty(),
                "GeneratedRestClientsModule must NOT be emitted when the only @RestClient interface is @NoAutoWire");
    }
}
