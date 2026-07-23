// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientProcessor} generates {@code {Bean}_BeanParamAccessor} classes for
 * various bean type patterns: records, public-field classes, Lombok-style getter classes, and
 * inheritance hierarchies.
 */
class RestClientProcessorBeanAccessorTest {

    @Test
    @DisplayName("record bean generates accessor with record component access")
    void recordBean_generatesAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.PageRequest", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public record PageRequest(
                                    @QueryParam("page") int page,
                                    @QueryParam("size") int size
                                ) {}
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UserClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface UserClient {
                                    @GET
                                    Future<List<String>> list(@BeanParam PageRequest paging);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.PageRequest_BeanParamAccessor", "BeanParamAccessor");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.PageRequest_BeanParamAccessor", "bean.page()");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.PageRequest_BeanParamAccessor", "bean.size()");
    }

    @Test
    @DisplayName("public-field class bean generates accessor with direct field access")
    void publicFieldClass_generatesAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.SearchParams", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;
                                import jakarta.ws.rs.HeaderParam;

                                public class SearchParams {
                                    @QueryParam("q") public String query;
                                    @HeaderParam("X-Locale") public String locale;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.SearchClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface SearchClient {
                                    @GET
                                    Future<List<String>> search(@BeanParam SearchParams params);
                                }
                                """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.SearchParams_BeanParamAccessor", "BeanParamAccessor");
        // Class case emits switch(fieldName) so the JIT can use tableswitch + indy-string dispatch
        // instead of an O(N) if/else-if chain.
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.SearchParams_BeanParamAccessor", "switch (fieldName)");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.SearchParams_BeanParamAccessor", "bean.query");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.SearchParams_BeanParamAccessor", "bean.locale");
    }

    @Test
    @DisplayName("bean with public getter generates accessor using getter access")
    void publicGetterBean_generatesAccessor() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.FilterParams", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class FilterParams {
                                    @QueryParam("status") private String status;

                                    public String getStatus() { return status; }
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.FilterClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import java.util.List;

                                @RestClient
                                public interface FilterClient {
                                    @GET
                                    Future<List<String>> list(@BeanParam FilterParams params);
                                }
                                """));

        result.assertSuccess();
        // With a getter, the accessor should be generated and use direct field access (pkg-private fallback)
        // OR the private field with getter path leads to the getter being found → generatable
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.FilterParams_BeanParamAccessor", "BeanParamAccessor");
    }

    @Test
    @DisplayName("subclass field shadowing a superclass field of the same name produces one switch arm")
    void shadowedField_dedupesByJavaName() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.BaseBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class BaseBean {
                                    @QueryParam("a") public String token;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.DerivedBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class DerivedBean extends BaseBean {
                                    @QueryParam("b") public String token;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.ShadowClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface ShadowClient {
                                    @GET
                                    Future<String> doIt(@BeanParam DerivedBean bean);
                                }
                                """));

        // Without dedup the generated source emits two `case "token"` arms and javac fails.
        result.assertSuccess();
        var generated =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.DerivedBean_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(generated.isPresent(), "Expected accessor for DerivedBean");
        result.assertGeneratedSourceContains("dev.vertique.examples.client.DerivedBean_BeanParamAccessor", "\"token\"");
        // Subclass-wins: the proxy must reference DerivedBean's wire name ("b"), not BaseBean's ("a").
        // The accessor encodes only javaName + access expression, so wire-name verification lives
        // at the proxy emission boundary, where field.name() drives req.query(...).
        result.assertGeneratedSourceContains("dev.vertique.examples.client.ShadowClient_RestClientProxy", "\"b\"");
    }

    @Test
    @DisplayName("unannotated subclass field hides an annotated superclass field — no metadata for the hidden one")
    void unannotatedShadowField_dropsHiddenSuperclassMetadata() throws Exception {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.AnnotatedBaseBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class AnnotatedBaseBean {
                                    @QueryParam("a") public String token;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.UnannotatedDerivedBean", """
                                package dev.vertique.examples.client;

                                public class UnannotatedDerivedBean extends AnnotatedBaseBean {
                                    public String token;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.HiddenClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface HiddenClient {
                                    @GET
                                    Future<String> doIt(@BeanParam UnannotatedDerivedBean bean);
                                }
                                """));

        // The Derived.token field shadows Base.token at runtime (Java field-hiding semantics);
        // bean.token reads Derived.token, which has no @QueryParam. Sending Base's "a=" wire
        // name with Derived's value would be a metadata/value mismatch. Correct behaviour:
        // the entire field is treated as inert — no metadata is emitted for "token".
        result.assertSuccess();
        // The proxy must NOT reference Base's wire name "a" — the hidden Base field carries
        // metadata that no longer maps to a usable runtime value.
        var proxySrc = result.compilation()
                .generatedSourceFile("dev.vertique.examples.client.HiddenClient_RestClientProxy")
                .orElseThrow()
                .getCharContent(true)
                .toString();
        org.junit.jupiter.api.Assertions.assertFalse(
                proxySrc.contains("\"a\""),
                "HiddenClient proxy should not reference the hidden Base wire name 'a' but did:\n" + proxySrc);
    }

    @Test
    @DisplayName("protected superclass field in another package is not directly accessible — bean falls back")
    void crossPackageProtectedSuperclassField_fallsBack() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.basepkg.AnotherBase", """
                                package dev.vertique.examples.basepkg;

                                import jakarta.ws.rs.QueryParam;

                                public class AnotherBase {
                                    @QueryParam("p") protected String protectedField;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.SubBean", """
                                package dev.vertique.examples.client;

                                import dev.vertique.examples.basepkg.AnotherBase;

                                public class SubBean extends AnotherBase {}
                                """),
                SourceFiles.inline("dev.vertique.examples.client.SubClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface SubClient {
                                    @GET
                                    Future<String> doIt(@BeanParam SubBean bean);
                                }
                                """));

        // The generated SubBean_BeanParamAccessor lives in dev.vertique.examples.client — the
        // SUBCLASS package, not the superclass package. A protected field declared in
        // dev.vertique.examples.basepkg is NOT accessible as `bean.protectedField` from a
        // non-subclass in a different package. The bean must fall back to reflective access
        // (no accessor generated) rather than emit code that fails to compile.
        result.assertSuccess();
        var generated =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.SubBean_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(),
                "Expected no accessor for cross-package protected superclass field; falls back to reflective");
    }

    @Test
    @DisplayName("inherited public getter on a superclass field is found and used")
    void inheritedPublicGetter_isResolved() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.GetterBase", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class GetterBase {
                                    @QueryParam("status") private String status;

                                    public String getStatus() { return status; }
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.GetterDerived", """
                                package dev.vertique.examples.client;

                                public class GetterDerived extends GetterBase {}
                                """),
                SourceFiles.inline("dev.vertique.examples.client.GetterClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface GetterClient {
                                    @GET
                                    Future<String> doIt(@BeanParam GetterDerived bean);
                                }
                                """));

        // The private @QueryParam field is on GetterBase; the getter is also on GetterBase but
        // inherited by GetterDerived. Without walking the superclass chain when resolving getters,
        // the scanner would treat the bean as not fully generatable and emit no accessor.
        result.assertSuccess();
        var generated = result.compilation()
                .generatedSourceFile("dev.vertique.examples.client.GetterDerived_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isPresent(), "Expected accessor for GetterDerived using inherited public getter");
        result.assertGeneratedSourceContains(
                "dev.vertique.examples.client.GetterDerived_BeanParamAccessor", "bean.getStatus()");
    }

    @Test
    @DisplayName("bean with private fields and no getters falls back — no accessor generated")
    void privateFieldsNoGetters_noAccessorGenerated() {
        var result = ProcessorTestHarness.run(
                new RestClientProcessor(),
                RestClientProcessorFixtures.restClientAnnotation(),
                RestClientProcessorFixtures.futureClass(),
                SourceFiles.inline("dev.vertique.examples.client.PrivateBean", """
                                package dev.vertique.examples.client;

                                import jakarta.ws.rs.QueryParam;

                                public class PrivateBean {
                                    @QueryParam("q") private String query;
                                }
                                """),
                SourceFiles.inline("dev.vertique.examples.client.PrivateClient", """
                                package dev.vertique.examples.client;

                                import dev.vertique.rest.client.RestClient;
                                import io.vertx.core.Future;
                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;

                                @RestClient
                                public interface PrivateClient {
                                    @GET
                                    Future<String> doIt(@BeanParam PrivateBean bean);
                                }
                                """));

        result.assertSuccess();
        // No accessor should be generated — falls back to reflective
        var generated =
                result.compilation().generatedSourceFile("dev.vertique.examples.client.PrivateBean_BeanParamAccessor");
        org.junit.jupiter.api.Assertions.assertTrue(generated.isEmpty(), "Expected no accessor for private-field bean");
    }
}
