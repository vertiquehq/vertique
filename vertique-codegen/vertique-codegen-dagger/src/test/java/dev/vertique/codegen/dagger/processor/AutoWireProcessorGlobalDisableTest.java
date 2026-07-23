// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that passing {@code -Avertique.codegen.autoWire=false} globally disables all code
 * generation from {@link AutoWireProcessor}, producing no generated source files for any marker.
 *
 * <p>JAX-RS {@code @Path} resource binding moved to {@code JaxRsPipelineProcessor} in
 * {@code vertique-codegen-jaxrs} (CG-010); the JAX-RS-specific {@code autoWire=false} contract
 * lives in {@code JaxRsPipelineProcessorTest}.
 */
class AutoWireProcessorGlobalDisableTest {

    private static final Map<String, String> DISABLED_OPTION = Map.of("vertique.codegen.autoWire", "false");

    @Test
    @DisplayName("-Avertique.codegen.autoWire=false disables @RestClient binding generation")
    void globalDisable_noRestClientsModule() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                DISABLED_OPTION,
                SourceFiles.inline("dev.vertique.rest.client.RestClient", """
                        package dev.vertique.rest.client;

                        import java.lang.annotation.*;

                        @Target(ElementType.TYPE)
                        @Retention(RetentionPolicy.RUNTIME)
                        @Documented
                        public @interface RestClient {
                            String name() default "";
                            String value() default "";
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                        package dev.vertique.examples.client;

                        import dev.vertique.rest.client.RestClient;

                        @RestClient(name = "userService", value = "http://localhost:8081")
                        public interface UserClient {}
                        """));

        result.assertSuccess();
        var generated =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.GeneratedRestClientsModule");
        Assertions.assertTrue(generated.isEmpty(), "Expected no generated module when autoWire=false");
    }
}
