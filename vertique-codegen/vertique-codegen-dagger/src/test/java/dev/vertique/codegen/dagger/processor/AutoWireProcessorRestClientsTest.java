// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AutoWireProcessor} generates {@code GeneratedRestClientsModule} containing
 * {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory factory)} methods
 * for interfaces annotated with {@code @RestClient}.
 */
class AutoWireProcessorRestClientsTest {

    private static final String GENERATED_MODULE_FQN = "dev.vertique.examples.client.GeneratedRestClientsModule";

    private static final String REST_CLIENT_ANNOTATION = """
            package dev.vertique.rest.client;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface RestClient {
                String name() default "";
                String value() default "";
            }
            """;

    private static final String REST_CLIENT_FACTORY = """
            package dev.vertique.rest.client;

            public class RestClientFactory {
                public Builder builder() { return new Builder(); }
                public static class Builder {
                    public <T> T build(Class<T> type) { return null; }
                }
            }
            """;

    @Test
    @DisplayName("@RestClient interface generates @Singleton direct binding")
    void restClientInterface_generatesSingletonBinding() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.rest.client.RestClient", REST_CLIENT_ANNOTATION),
                SourceFiles.inline("dev.vertique.rest.client.RestClientFactory", REST_CLIENT_FACTORY),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                        package dev.vertique.examples.client;

                        import dev.vertique.rest.client.RestClient;

                        @RestClient(name = "userService", value = "http://localhost:8081")
                        public interface UserClient {
                            String getUser(String id);
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Module");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Singleton");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "provideUserClient");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "RestClientFactory");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "factory.builder().build(UserClient.class)");
    }

    @Test
    @DisplayName("@RestClient interface with @NoAutoWire is excluded")
    void restClientInterface_noAutoWire_excluded() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.rest.client.RestClient", REST_CLIENT_ANNOTATION),
                SourceFiles.inline("dev.vertique.rest.client.RestClientFactory", REST_CLIENT_FACTORY),
                SourceFiles.inline("dev.vertique.codegen.NoAutoWire", """
                        package dev.vertique.codegen;

                        import java.lang.annotation.*;

                        @Target(ElementType.TYPE)
                        @Retention(RetentionPolicy.SOURCE)
                        @Documented
                        public @interface NoAutoWire {}
                        """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                        package dev.vertique.examples.client;

                        import dev.vertique.rest.client.RestClient;
                        import dev.vertique.codegen.NoAutoWire;

                        @NoAutoWire
                        @RestClient(name = "userService", value = "http://localhost:8081")
                        public interface UserClient {
                            String getUser(String id);
                        }
                        """));

        result.assertSuccess();
        var generated = result.compilation().generatedSourceFile(GENERATED_MODULE_FQN);
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(), "Expected no generated module when @NoAutoWire is present");
    }

    @Test
    @DisplayName("compile validation: generated RestClients module compiles with Dagger annotations")
    void generatedModule_compilesAgainstRealDagger() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.rest.client.RestClient", REST_CLIENT_ANNOTATION),
                SourceFiles.inline("dev.vertique.rest.client.RestClientFactory", REST_CLIENT_FACTORY),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                        package dev.vertique.examples.client;

                        import dev.vertique.rest.client.RestClient;

                        @RestClient(name = "userService", value = "http://localhost:8081")
                        public interface UserClient {
                            String getUser(String id);
                        }
                        """));

        result.assertSuccess();
    }
}
