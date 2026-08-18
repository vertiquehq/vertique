// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.ChainResolver;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.GeneratedInputProcessor;
import dev.vertique.input.processing.GeneratedInputProcessorDispatcher;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.input.processing.InputTraversalContext;
import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * End-to-end roundtrip integration test for {@link SanitizationProcessor}.
 *
 * <p>Compiles fixture DTOs and their owning JAX-RS resources via the test harness, loads
 * the generated {@code _InputProcessor} classes via the harness classloader, instantiates
 * them, and invokes {@code process()} to assert the correct transformation outcomes:
 * string canonicalization/sanitization, collection-of-strings, nested DTO dispatch,
 * skip flags, and route-level policy propagation.
 *
 * <p>The roundtrip uses real built-in canonicalizers/sanitizers from {@code vertique-sanitization}
 * (available on the test classpath) to confirm the full data path from annotation to execution.
 */
class SanitizationProcessorRoundtripTest {

    // --- Shared fixture sources ---

    /** Nested DTO with a @Canonicalize-annotated field. Used by the full-fixture test. */
    private static final JavaFileObject COMMENT_DTO = SourceFiles.inline("com.example.rt.CommentDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class CommentDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String text;
            }
            """);

    /** Top-level DTO exercising all field kinds. */
    private static final JavaFileObject ARTICLE_DTO = SourceFiles.inline("com.example.rt.ArticleDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.core.sanitization.SkipCanonicalization;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            import dev.vertique.sanitization.canonicalize.LowerCaseCanonicalizer;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            public class ArticleDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String title;
                @Canonicalize({TrimCanonicalizer.class, LowerCaseCanonicalizer.class})
                public String slug;
                @Sanitize(StripControlCharsSanitizer.class)
                public String content;
                @Canonicalize(TrimCanonicalizer.class)
                public List<String> tags;
                public CommentDto comment;
                public List<CommentDto> comments;
                public int count;
                @SkipCanonicalization
                public String skipMe;
            }
            """);

    private static final JavaFileObject ARTICLE_RESOURCE = SourceFiles.inline("com.example.rt.ArticleResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/articles")
            public class ArticleResource {
                @POST
                public String create(ArticleDto body) { return null; }
            }
            """);

    // --- Simple flat DTO for isolated tests ---

    private static final JavaFileObject SIMPLE_DTO = SourceFiles.inline("com.example.rt.SimpleDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.core.sanitization.SkipCanonicalization;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class SimpleDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String trimmed;
                public String plain;
                @SkipCanonicalization
                public String skipMe;
            }
            """);

    private static final JavaFileObject SIMPLE_RESOURCE = SourceFiles.inline("com.example.rt.SimpleResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/simple")
            public class SimpleResource {
                @POST
                public String create(SimpleDto body) { return null; }
            }
            """);

    // --- Optional-wrapped fields ---

    /**
     * Nested DTO reachable from {@link #OPT_PROFILE_DTO} only through an {@code Optional<...>}
     * wrapper. Carries its own {@code @Sanitize} so the scanner must emit
     * {@code OptCommentDto_InputProcessor} for the nested dispatch to have a target.
     */
    private static final JavaFileObject OPT_COMMENT_DTO = SourceFiles.inline("com.example.rt.OptCommentDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class OptCommentDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String text;
            }
            """);

    /**
     * DTO whose sanitized fields are all wrapped in {@link java.util.Optional} — directly
     * ({@code Optional<String>}, {@code Optional<OptCommentDto>}) and as a collection element
     * ({@code List<Optional<String>>}). The wire representation of each is the unwrapped value,
     * so classification must see through the wrapper.
     */
    private static final JavaFileObject OPT_PROFILE_DTO = SourceFiles.inline("com.example.rt.OptProfileDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            import java.util.Optional;
            public class OptProfileDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public Optional<String> nickname;
                public Optional<OptCommentDto> comment;
                @Sanitize(StripControlCharsSanitizer.class)
                public List<Optional<String>> aliases;
            }
            """);

    private static final JavaFileObject OPT_PROFILE_RESOURCE =
            SourceFiles.inline("com.example.rt.OptProfileResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/opt-profiles")
            public class OptProfileResource {
                @POST
                public String create(OptProfileDto body) { return null; }
            }
            """);

    // --- Bounded generic (wildcard / type-variable) fields ---

    /**
     * Generic superclass carrying an {@code Optional<T>} field whose type variable is upper-bounded
     * by {@link #OPT_COMMENT_DTO}. Declared on a supertype so the concrete root DTO stays
     * non-generic while the collector still sees a {@code TYPEVAR} type argument.
     */
    private static final JavaFileObject BOUNDED_BASE_DTO = SourceFiles.inline("com.example.rt.BoundedBaseDto", """
            package com.example.rt;
            import java.util.Optional;
            public class BoundedBaseDto<T extends OptCommentDto> {
                public Optional<T> typeVarComment;
            }
            """);

    /**
     * Root DTO whose nested-DTO fields are reachable only behind bounded generics — wildcard
     * type arguments ({@code ? extends OptCommentDto}) directly, inside an {@code Optional}, and
     * as a collection element. Jackson resolves each upper bound and materializes a real
     * {@code OptCommentDto}, so classification must do the same.
     */
    private static final JavaFileObject BOUNDED_PROFILE_DTO =
            SourceFiles.inline("com.example.rt.BoundedProfileDto", """
            package com.example.rt;
            import java.util.List;
            import java.util.Optional;
            public class BoundedProfileDto extends BoundedBaseDto<OptCommentDto> {
                public Optional<? extends OptCommentDto> boundedComment;
                public List<Optional<? extends OptCommentDto>> boundedOptionalComments;
                public List<? extends OptCommentDto> boundedComments;
            }
            """);

    private static final JavaFileObject BOUNDED_PROFILE_RESOURCE =
            SourceFiles.inline("com.example.rt.BoundedProfileResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/bounded-profiles")
            public class BoundedProfileResource {
                @POST
                public String create(BoundedProfileDto body) { return null; }
            }
            """);

    // --- Unbounded / lower-bounded wildcards (negative pins) ---

    /** Annotated DTO that must <em>not</em> become reachable through an unbounded wildcard. */
    private static final JavaFileObject NEG_COMMENT_DTO = SourceFiles.inline("com.example.rt.NegCommentDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class NegCommentDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String text;
            }
            """);

    /**
     * Root DTO whose only {@code Optional} fields carry an unbounded ({@code ?}) or lower-bounded
     * ({@code ? super X}) wildcard. Neither carries an upper bound above {@code java.lang.Object},
     * which is exactly what Jackson materializes for them — so both stay
     * {@code FieldKind.OTHER} and never drag {@link #NEG_COMMENT_DTO} into the emit set.
     */
    private static final JavaFileObject NEG_PROFILE_DTO = SourceFiles.inline("com.example.rt.NegProfileDto", """
            package com.example.rt;
            import java.util.Optional;
            public class NegProfileDto {
                public Optional<?> anything;
                public Optional<? super NegCommentDto> superComment;
            }
            """);

    private static final JavaFileObject NEG_PROFILE_RESOURCE =
            SourceFiles.inline("com.example.rt.NegProfileResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/neg-profiles")
            public class NegProfileResource {
                @POST
                public String create(NegProfileDto body) { return null; }
            }
            """);

    // --- Array-typed fields ---

    /**
     * Nested DTO reachable from {@link #ARRAY_ARTICLE_DTO} only as an <em>array component</em>
     * type. Carries its own {@code @Sanitize} so the scanner must emit
     * {@code ArrayCommentDto_InputProcessor} for the element-wise dispatch to have a target.
     */
    private static final JavaFileObject ARRAY_COMMENT_DTO = SourceFiles.inline("com.example.rt.ArrayCommentDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class ArrayCommentDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String text;
            }
            """);

    /**
     * Root DTO covering the three array shapes the reflective walker distinguishes: an array of
     * nested DTOs and an array of strings both carry one element schema and descend element-wise,
     * while an array of arrays carries none and must keep the inherited-chain-only path.
     */
    private static final JavaFileObject ARRAY_ARTICLE_DTO = SourceFiles.inline("com.example.rt.ArrayArticleDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class ArrayArticleDto {
                public ArrayCommentDto[] comments;
                @Sanitize(StripControlCharsSanitizer.class)
                public String[] tags;
                @Sanitize(StripControlCharsSanitizer.class)
                public String[][] matrix;
            }
            """);

    private static final JavaFileObject ARRAY_ARTICLE_RESOURCE =
            SourceFiles.inline("com.example.rt.ArrayArticleResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/array-articles")
            public class ArrayArticleResource {
                @POST
                public String create(ArrayArticleDto body) { return null; }
            }
            """);

    // --- Nested-container and static-field fixtures ---

    /**
     * Nested DTO used as the innermost element of a doubly-nested container field, and as the type
     * of {@link #NESTED_CONTAINER_DTO}'s static field. Carries its own {@code @Sanitize} so an
     * unwanted element-wise dispatch or a resolved static field would be observable as an emitted
     * {@code NestedTagDto_InputProcessor} reference.
     */
    private static final JavaFileObject NESTED_TAG_DTO = SourceFiles.inline("com.example.rt.NestedTagDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class NestedTagDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String text;
            }
            """);

    /**
     * Root DTO covering the shapes whose element type is itself a container ({@code List<List<…>>},
     * {@code Set<List<…>>}), plus a {@code @Slf4j}-style static field. None of the three is a
     * dispatchable element schema or a wire property, so all three must keep the
     * inherited-chain-only path — exactly as the reflective walker classifies them.
     */
    private static final JavaFileObject NESTED_CONTAINER_DTO =
            SourceFiles.inline("com.example.rt.NestedContainerDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            import java.util.Set;
            public class NestedContainerDto {
                public static final NestedTagDto SHARED = new NestedTagDto();
                @Sanitize(StripControlCharsSanitizer.class)
                public List<List<String>> rows;
                public Set<List<NestedTagDto>> tagGroups;
            }
            """);

    private static final JavaFileObject NESTED_CONTAINER_RESOURCE =
            SourceFiles.inline("com.example.rt.NestedContainerResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/nested-containers")
            public class NestedContainerResource {
                @POST
                public String create(NestedContainerDto body) { return null; }
            }
            """);

    // --- Wire-name projection fixtures ---

    /**
     * Nested DTO whose only annotated property is reached under a renamed wire key
     * ({@code city_name} → {@code cityName}), so the nested generated processor must itself project
     * before its switch — a projection that only worked at the root would leave this field untouched.
     */
    private static final JavaFileObject RENAMED_ADDRESS_DTO =
            SourceFiles.inline("com.example.rt.RenamedAddressDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class RenamedAddressDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String cityName;
            }
            """);

    /**
     * Root DTO whose Java property names differ from the wire keys the intermediate is keyed by.
     * Covers the {@code STRING} arm ({@code user_name} → {@code userName}) and the
     * {@code NESTED_DTO} arm ({@code home_address} → {@code homeAddress}).
     */
    private static final JavaFileObject RENAMED_PROFILE_DTO =
            SourceFiles.inline("com.example.rt.RenamedProfileDto", """
            package com.example.rt;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class RenamedProfileDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String userName;
                public RenamedAddressDto homeAddress;
            }
            """);

    private static final JavaFileObject RENAMED_PROFILE_RESOURCE =
            SourceFiles.inline("com.example.rt.RenamedProfileResource", """
            package com.example.rt;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/renamed-profiles")
            public class RenamedProfileResource {
                @POST
                public String create(RenamedProfileDto body) { return null; }
            }
            """);

    // --- Simple flat string canonicalization ---

    @Nested
    @DisplayName("simple flat DTO string canonicalization")
    class FlatStringCanonicalization {

        @Test
        @DisplayName("@Canonicalize(TrimCanonicalizer) field has leading/trailing whitespace stripped")
        @SuppressWarnings("unchecked")
        void trimAnnotatedField_whiteSpaceStripped() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), SIMPLE_DTO, SIMPLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.SimpleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("trimmed", "  hello world  ");
            intermediate.put("plain", "unchanged");
            intermediate.put("skipMe", "  should stay  ");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals("hello world", out.get("trimmed"), "TrimCanonicalizer should strip whitespace");
            assertEquals("unchanged", out.get("plain"), "Unannotated field should pass through unchanged");
        }

        @Test
        @DisplayName("@SkipCanonicalization field with route-level Trim policy — field passes through unchanged")
        @SuppressWarnings("unchecked")
        void skipCanonField_routePolicyIgnored() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), SIMPLE_DTO, SIMPLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.SimpleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("skipMe", "  preserve whitespace  ");

            // Route-level trim policy — but skipMe has @SkipCanonicalization, so it should not apply
            EffectiveInputPolicies routePolicy =
                    new EffectiveInputPolicies(List.of(TrimCanonicalizer.class), List.of());

            Object output = processor.process(
                    intermediate,
                    routePolicy,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    "  preserve whitespace  ",
                    out.get("skipMe"),
                    "@SkipCanonicalization should suppress route-level Trim on this field");
        }

        @Test
        @DisplayName("route-level canonicalization applies to unannotated plain field")
        @SuppressWarnings("unchecked")
        void routeLevelCanon_appliedToUnannotatedField() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), SIMPLE_DTO, SIMPLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.SimpleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("plain", "  route-trimmed  ");

            EffectiveInputPolicies routePolicy =
                    new EffectiveInputPolicies(List.of(TrimCanonicalizer.class), List.of());

            Object output = processor.process(
                    intermediate,
                    routePolicy,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    "route-trimmed",
                    out.get("plain"),
                    "Route-level TrimCanonicalizer should trim unannotated plain field");
        }
    }

    // --- Multi-step canonicalization chain ---

    @Nested
    @DisplayName("multi-step canonicalization chain")
    class MultiStepChain {

        @Test
        @DisplayName("@Canonicalize({Trim, LowerCase}) chain applies both canonicalizers in order")
        @SuppressWarnings("unchecked")
        void trimAndLowerCaseChain_appliedInOrder() throws Exception {
            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), COMMENT_DTO, ARTICLE_DTO, ARTICLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.ArticleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("slug", "  My Slug  ");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals("my slug", out.get("slug"), "Trim + LowerCase chain should first strip spaces then lowercase");
        }
    }

    // --- Sanitizer application ---

    @Nested
    @DisplayName("sanitizer application")
    class SanitizerApplication {

        @Test
        @DisplayName("@Sanitize(StripControlChars) removes control characters from field value")
        @SuppressWarnings("unchecked")
        void sanitizeField_controlCharsStripped() throws Exception {
            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), COMMENT_DTO, ARTICLE_DTO, ARTICLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.ArticleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("content", "hello\u0001world"); // U+0001 is a C0 control character

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals("helloworld", out.get("content"), "StripControlCharsSanitizer should remove U+0001");
        }
    }

    // --- Collection of strings ---

    @Nested
    @DisplayName("collection of strings field")
    class CollectionOfStrings {

        @Test
        @DisplayName("@Canonicalize on List<String> field — each element trimmed")
        @SuppressWarnings("unchecked")
        void listOfStringsField_elementsAreTrimmed() throws Exception {
            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), COMMENT_DTO, ARTICLE_DTO, ARTICLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.ArticleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            List<Object> tags = new ArrayList<>(List.of("  java  ", "  vertx  ", 42)); // mixed types
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("tags", tags);

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(List.class, out.get("tags"));
            List<Object> processedTags = (List<Object>) out.get("tags");
            assertEquals(3, processedTags.size());
            assertEquals("java", processedTags.get(0), "First tag should be trimmed");
            assertEquals("vertx", processedTags.get(1), "Second tag should be trimmed");
            assertEquals(42, processedTags.get(2), "Non-string element should pass through unchanged");
        }
    }

    // --- Nested DTO dispatch ---

    @Nested
    @DisplayName("nested DTO field dispatch")
    class NestedDtoDispatch {

        @Test
        @DisplayName("nested DTO field dispatches through dispatcher; CommentDto_InputProcessor applies Trim")
        @SuppressWarnings("unchecked")
        void nestedDtoField_dispatchedAndProcessed() throws Exception {
            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), COMMENT_DTO, ARTICLE_DTO, ARTICLE_RESOURCE);
            result.assertSuccess();

            // Load both generated processors
            Class<?> articleProcessorClass = result.loadGeneratedClass("com.example.rt.ArticleDto_InputProcessor");
            Class<?> commentProcessorClass = result.loadGeneratedClass("com.example.rt.CommentDto_InputProcessor");

            GeneratedInputProcessor<?> articleProcessor = (GeneratedInputProcessor<?>)
                    articleProcessorClass.getDeclaredConstructor().newInstance();
            GeneratedInputProcessor<?> commentProcessor = (GeneratedInputProcessor<?>)
                    commentProcessorClass.getDeclaredConstructor().newInstance();

            // Build a dispatcher that serves the comment processor
            GeneratedInputProcessorDispatcher dispatcher = new GeneratedInputProcessorDispatcher(
                    new GeneratedInputProcessorDispatcher.ReflectiveContinuation() {
                        @Override
                        public Object continueAt(
                                Object inter,
                                Class<?> type,
                                dev.vertique.input.processing.InputTraversalContext ctx,
                                InputLocation location,
                                String fieldPath,
                                Class<?> ownerType) {
                            throw new UnsupportedOperationException("Reflective fallback not expected");
                        }

                        @Override
                        public Object walkUnknown(
                                Object inter,
                                dev.vertique.input.processing.InputTraversalContext ctx,
                                InputLocation location,
                                String fieldPath,
                                Class<?> ownerType) {
                            throw new UnsupportedOperationException("walkUnknown not expected");
                        }
                    });

            // Register the comment processor so the article processor can dispatch to it.
            // We need the Class object from the harness classloader — use the targetType() method.
            Class<?> commentDtoClass = commentProcessor.targetType();
            registerProcessor(dispatcher, commentDtoClass, commentProcessor);

            Map<String, Object> nestedMap = new LinkedHashMap<>();
            nestedMap.put("text", "  hello  ");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("comment", nestedMap);

            Object output = articleProcessor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(Map.class, out.get("comment"));
            Map<String, Object> processedComment = (Map<String, Object>) out.get("comment");
            assertEquals(
                    "hello",
                    processedComment.get("text"),
                    "CommentDto.text should be trimmed by its @Canonicalize(TrimCanonicalizer)");
        }
    }

    // --- Optional-wrapped fields ---

    /**
     * Proves that {@code Optional<...>}-wrapped fields are sanitized on the generated path.
     *
     * <p>Every test here runs against a dispatcher whose reflective continuation
     * <em>throws</em> ({@link GeneratedInputProcessorDispatcher#withoutContinuation()}), so a
     * generated arm that falls back to {@code continueAt} / {@code walkUnknown} fails loudly
     * instead of silently returning the value untouched.
     */
    @Nested
    @DisplayName("Optional-wrapped fields")
    class OptionalWrappedFields {

        @Test
        @DisplayName("@Sanitize on Optional<String> field — control characters stripped, no reflective fallback")
        @SuppressWarnings("unchecked")
        void optionalStringField_sanitized() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), OPT_COMMENT_DTO, OPT_PROFILE_DTO, OPT_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> processor = newProcessor(result, "com.example.rt.OptProfileDto_InputProcessor");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("nickname", "nick\u0001name");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    "nickname",
                    out.get("nickname"),
                    "@Sanitize on Optional<String> must apply to the unwrapped string value");
        }

        @Test
        @DisplayName("Optional<CommentDto> field dispatches to OptCommentDto_InputProcessor")
        @SuppressWarnings("unchecked")
        void optionalNestedDtoField_dispatchedToNestedProcessor() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), OPT_COMMENT_DTO, OPT_PROFILE_DTO, OPT_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> profileProcessor =
                    newProcessor(result, "com.example.rt.OptProfileDto_InputProcessor");
            GeneratedInputProcessor<?> commentProcessor =
                    newProcessor(result, "com.example.rt.OptCommentDto_InputProcessor");

            GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            registerProcessor(dispatcher, commentProcessor.targetType(), commentProcessor);

            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("text", "hel\u0001lo");
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("comment", nested);

            Object output = profileProcessor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(Map.class, out.get("comment"));
            Map<String, Object> processedComment = (Map<String, Object>) out.get("comment");
            assertEquals(
                    "hello",
                    processedComment.get("text"),
                    "Optional<OptCommentDto> must resolve field metadata from OptCommentDto, not Optional");
        }

        @Test
        @DisplayName("@Sanitize on List<Optional<String>> field — each element sanitized")
        @SuppressWarnings("unchecked")
        void listOfOptionalStrings_eachElementSanitized() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), OPT_COMMENT_DTO, OPT_PROFILE_DTO, OPT_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> processor = newProcessor(result, "com.example.rt.OptProfileDto_InputProcessor");

            List<Object> aliases = new ArrayList<>(List.of("a\u0001lpha", "be\u0001ta"));
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("aliases", aliases);

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(List.class, out.get("aliases"));
            List<Object> processed = (List<Object>) out.get("aliases");
            assertEquals(List.of("alpha", "beta"), processed, "Each List<Optional<String>> element must be sanitized");
        }

        // --- Bounded generics: wildcard and type-variable upper bounds ---

        @Test
        @DisplayName("Optional<? extends OptCommentDto> field dispatches to OptCommentDto_InputProcessor")
        @SuppressWarnings("unchecked")
        void optionalWildcardBoundedNestedDto_dispatchedToNestedProcessor() throws Exception {
            Map<String, Object> out = processBounded("boundedComment", nestedComment("hel\u0001lo"));

            assertInstanceOf(Map.class, out.get("boundedComment"));
            Map<String, Object> processed = (Map<String, Object>) out.get("boundedComment");
            assertEquals(
                    "hello",
                    processed.get("text"),
                    "Optional<? extends OptCommentDto> must classify against the wildcard's upper bound");
        }

        @Test
        @DisplayName("Optional<T extends OptCommentDto> field dispatches to OptCommentDto_InputProcessor")
        @SuppressWarnings("unchecked")
        void optionalTypeVariableBoundedNestedDto_dispatchedToNestedProcessor() throws Exception {
            Map<String, Object> out = processBounded("typeVarComment", nestedComment("wor\u0001ld"));

            assertInstanceOf(Map.class, out.get("typeVarComment"));
            Map<String, Object> processed = (Map<String, Object>) out.get("typeVarComment");
            assertEquals(
                    "world",
                    processed.get("text"),
                    "Optional<T extends OptCommentDto> must classify against the type variable's upper bound");
        }

        @Test
        @DisplayName("List<Optional<? extends OptCommentDto>> field dispatches each element")
        @SuppressWarnings("unchecked")
        void listOfOptionalWildcardBoundedDto_eachElementDispatched() throws Exception {
            List<Object> elements = new ArrayList<>(List.of(nestedComment("al\u0001pha"), nestedComment("be\u0001ta")));

            Map<String, Object> out = processBounded("boundedOptionalComments", elements);

            assertInstanceOf(List.class, out.get("boundedOptionalComments"));
            List<Object> processed = (List<Object>) out.get("boundedOptionalComments");
            assertEquals(
                    List.of("alpha", "beta"),
                    processed.stream()
                            .map(e -> ((Map<String, Object>) e).get("text"))
                            .toList(),
                    "Each List<Optional<? extends OptCommentDto>> element must reach OptCommentDto_InputProcessor");
        }

        @Test
        @DisplayName("List<? extends OptCommentDto> field dispatches each element")
        @SuppressWarnings("unchecked")
        void listOfWildcardBoundedDto_eachElementDispatched() throws Exception {
            List<Object> elements = new ArrayList<>(List.of(nestedComment("ga\u0001mma")));

            Map<String, Object> out = processBounded("boundedComments", elements);

            assertInstanceOf(List.class, out.get("boundedComments"));
            List<Object> processed = (List<Object>) out.get("boundedComments");
            assertEquals(
                    List.of("gamma"),
                    processed.stream()
                            .map(e -> ((Map<String, Object>) e).get("text"))
                            .toList(),
                    "Each List<? extends OptCommentDto> element must reach OptCommentDto_InputProcessor");
        }

        // --- Negative pins: unbounded and lower-bounded wildcards stay OTHER ---

        @Test
        @DisplayName("Optional<?> and Optional<? super X> stay OTHER — no nested dispatch, value passthrough")
        @SuppressWarnings("unchecked")
        void unboundedAndSuperWildcardOptionals_stayOtherAndPassThrough() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), NEG_COMMENT_DTO, NEG_PROFILE_DTO, NEG_PROFILE_RESOURCE);
            result.assertSuccess();
            result.assertGeneratedSourceDoesNotContain("com.example.rt.NegProfileDto_InputProcessor", "NegCommentDto");

            GeneratedInputProcessor<?> processor = newProcessor(result, "com.example.rt.NegProfileDto_InputProcessor");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("anything", "a\u0001b");
            intermediate.put("superComment", "x\u0001y");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals("a\u0001b", out.get("anything"), "Optional<?> carries no upper bound — value passes through");
            assertEquals(
                    "x\u0001y",
                    out.get("superComment"),
                    "Optional<? super X> carries no upper bound above Object — value passes through");
        }

        // --- Bounded-fixture helpers ---

        /**
         * Compiles the bounded-generics fixture set, registers the generated
         * {@code OptCommentDto_InputProcessor} with a continuation-free dispatcher, and runs the
         * root processor over a single-entry intermediate map.
         *
         * @param fieldName the {@code BoundedProfileDto} field to populate
         * @param value     the intermediate wire value for that field
         * @return the processed output map
         * @throws Exception if compilation or processor instantiation fails
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> processBounded(String fieldName, Object value) throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(),
                    OPT_COMMENT_DTO,
                    BOUNDED_BASE_DTO,
                    BOUNDED_PROFILE_DTO,
                    BOUNDED_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> profileProcessor =
                    newProcessor(result, "com.example.rt.BoundedProfileDto_InputProcessor");
            GeneratedInputProcessor<?> commentProcessor =
                    newProcessor(result, "com.example.rt.OptCommentDto_InputProcessor");

            GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            registerProcessor(dispatcher, commentProcessor.targetType(), commentProcessor);

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put(fieldName, value);

            Object output = profileProcessor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            return (Map<String, Object>) output;
        }

        /**
         * Builds a one-field intermediate map standing in for a serialized {@code OptCommentDto}.
         * Callers embed a control character in {@code rawText} so that a successful dispatch to
         * {@code OptCommentDto_InputProcessor} is observable — its
         * {@code @Sanitize(StripControlCharsSanitizer)} chain strips it.
         *
         * @param rawText the un-sanitized {@code text} wire value
         * @return the nested intermediate map
         */
        private Map<String, Object> nestedComment(String rawText) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("text", rawText);
            return nested;
        }
    }

    // --- Array-typed fields ---

    /**
     * Proves that array-typed fields descend element-wise on the <em>generated</em> path, matching
     * the reflective walker's rule that a collection and an array are one shape:
     * {@code InputPolicyMetadataResolver.buildFieldMeta} routes
     * {@code Collection.class.isAssignableFrom(rawType) || rawType.isArray()} through a single
     * branch, with {@code TypeClassifier.elementType} supplying the element schema — and returning
     * {@code null} when the component type is itself an array, which keeps
     * {@code String[][]} on the inherited-chain path.
     *
     * <p>These assertions run against real generated processor classes, not hand-written
     * companions, so they pin what the annotation processor actually emits.
     */
    @Nested
    @DisplayName("array-typed fields")
    class ArrayFields {

        @Test
        @DisplayName("ArrayCommentDto[] field dispatches each element at the component type")
        @SuppressWarnings("unchecked")
        void arrayOfNestedDtoField_eachElementDispatchedAtComponentType() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), ARRAY_COMMENT_DTO, ARRAY_ARTICLE_DTO, ARRAY_ARTICLE_RESOURCE);
            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "com.example.rt.ArrayArticleDto_InputProcessor",
                    "dispatchObjectCollection(v, ArrayCommentDto.class");

            GeneratedInputProcessor<?> articleProcessor =
                    newProcessor(result, "com.example.rt.ArrayArticleDto_InputProcessor");
            GeneratedInputProcessor<?> commentProcessor =
                    newProcessor(result, "com.example.rt.ArrayCommentDto_InputProcessor");

            GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            registerProcessor(dispatcher, commentProcessor.targetType(), commentProcessor);

            List<Object> elements = new ArrayList<>(List.of(arrayComment("al\u0001pha"), arrayComment("be\u0001ta")));
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("comments", elements);

            Object output = articleProcessor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(List.class, out.get("comments"));
            List<Object> processed = (List<Object>) out.get("comments");
            assertEquals(
                    List.of("alpha", "beta"),
                    processed.stream()
                            .map(e -> ((Map<String, Object>) e).get("text"))
                            .toList(),
                    "Each ArrayCommentDto[] element must reach ArrayCommentDto_InputProcessor, "
                            + "exactly as a List<ArrayCommentDto> element does");
        }

        @Test
        @DisplayName("@Sanitize on String[] field — each element sanitized")
        @SuppressWarnings("unchecked")
        void arrayOfStringsField_eachElementSanitized() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), ARRAY_COMMENT_DTO, ARRAY_ARTICLE_DTO, ARRAY_ARTICLE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> processor =
                    newProcessor(result, "com.example.rt.ArrayArticleDto_InputProcessor");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("tags", new ArrayList<>(List.of("ja\u0001va", "ve\u0001rtx")));

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    List.of("java", "vertx"),
                    out.get("tags"),
                    "A String[] field carries the same element schema as a List<String> and must be "
                            + "sanitized element-wise without a reflective fallback");
        }

        @Test
        @DisplayName("String[][] field has no element schema — keeps the inherited-chain path")
        @SuppressWarnings("unchecked")
        void arrayOfArraysField_keepsInheritedChainPath() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), ARRAY_COMMENT_DTO, ARRAY_ARTICLE_DTO, ARRAY_ARTICLE_RESOURCE);
            result.assertSuccess();
            result.assertGeneratedSourceDoesNotContain(
                    "com.example.rt.ArrayArticleDto_InputProcessor", "dispatchObjectCollection(v, String[]");

            GeneratedInputProcessor<?> processor =
                    newProcessor(result, "com.example.rt.ArrayArticleDto_InputProcessor");

            RecordingContinuation continuation = new RecordingContinuation();
            GeneratedInputProcessorDispatcher dispatcher = new GeneratedInputProcessorDispatcher(continuation);

            List<Object> rows = new ArrayList<>(List.of(new ArrayList<>(List.of("ab"))));
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("matrix", rows);

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    List.of("matrix"),
                    continuation.walkUnknownPaths,
                    "String[][] has no element schema (TypeClassifier.elementType returns null for an "
                            + "array component), so it must reach the reflective continuation rather than "
                            + "dispatching elements at String[]");
            assertSame(rows, out.get("matrix"), "The continuation's return value is what lands in the output map");
        }

        /**
         * Builds a one-field intermediate map standing in for a serialized {@code ArrayCommentDto}.
         * Callers embed a control character in {@code rawText} so a successful element dispatch is
         * observable — {@code ArrayCommentDto_InputProcessor} strips it.
         *
         * @param rawText the un-sanitized {@code text} wire value
         * @return the nested intermediate map
         */
        private Map<String, Object> arrayComment(String rawText) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("text", rawText);
            return nested;
        }
    }

    /**
     * Reflective continuation that records the field paths reaching {@code walkUnknown} and returns
     * the intermediate unchanged. {@code continueAt} throws, so an unexpected typed descent is as
     * loud as {@link GeneratedInputProcessorDispatcher#withoutContinuation()} makes it.
     */
    private static final class RecordingContinuation
            implements GeneratedInputProcessorDispatcher.ReflectiveContinuation {

        private final List<String> walkUnknownPaths = new ArrayList<>();

        @Override
        public Object continueAt(
                Object intermediate,
                Class<?> targetType,
                dev.vertique.input.processing.InputTraversalContext ctx,
                InputLocation location,
                String fieldPath,
                Class<?> ownerType) {
            throw new UnsupportedOperationException("continueAt not expected for " + fieldPath);
        }

        @Override
        public Object walkUnknown(
                Object intermediate,
                dev.vertique.input.processing.InputTraversalContext ctx,
                InputLocation location,
                String fieldPath,
                Class<?> ownerType) {
            walkUnknownPaths.add(fieldPath);
            return intermediate;
        }
    }

    // --- Null value passthrough ---

    @Nested
    @DisplayName("null value handling")
    class NullValueHandling {

        @Test
        @DisplayName("null field value passes through unchanged without NPE")
        @SuppressWarnings("unchecked")
        void nullFieldValue_passesThroughUnchanged() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), SIMPLE_DTO, SIMPLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.SimpleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("trimmed", null);
            intermediate.put("plain", "hello");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertNotNull(out, "Output map should not be null");
            assertEquals(null, out.get("trimmed"), "Null value should pass through as null");
            assertEquals("hello", out.get("plain"), "Other field should remain unchanged");
        }
    }

    // --- Non-map intermediate passthrough ---

    @Nested
    @DisplayName("non-map intermediate passthrough")
    class NonMapIntermediate {

        @Test
        @DisplayName("non-Map intermediate returned unchanged")
        @SuppressWarnings("unchecked")
        void nonMapIntermediate_returnedUnchanged() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), SIMPLE_DTO, SIMPLE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.rt.SimpleDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();

            String nonMap = "not a map";
            Object output = processor.process(
                    nonMap,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    null,
                    "");

            assertEquals(nonMap, output, "Non-Map intermediate should be returned unchanged");
        }
    }

    // --- Nested containers and static fields ---

    /**
     * Proves the APT-time classifier agrees with {@code TypeClassifier} on the two shapes that are
     * not wire-dispatchable: a container whose element type is itself a container, and a static
     * field. Both must reach the reflective continuation's {@code walkUnknown} (or not be emitted
     * at all) rather than dispatching at a bogus element or field type.
     *
     * <p>Divergence here is not cosmetic: the generated and reflective paths must produce
     * byte-equal output for the same intermediate, so a shape the reflective walker leaves on the
     * inherited-chain path cannot be element-dispatched by codegen.
     */
    @Nested
    @DisplayName("nested container and static fields")
    class NestedContainerAndStaticFields {

        @Test
        @DisplayName("List<List<String>> has no element schema — keeps the inherited-chain path")
        @SuppressWarnings("unchecked")
        void nestedListField_keepsInheritedChainPath() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), NESTED_TAG_DTO, NESTED_CONTAINER_DTO, NESTED_CONTAINER_RESOURCE);
            result.assertSuccess();
            result.assertGeneratedSourceDoesNotContain(
                    "com.example.rt.NestedContainerDto_InputProcessor", "dispatchObjectCollection(v, List");

            GeneratedInputProcessor<?> processor =
                    newProcessor(result, "com.example.rt.NestedContainerDto_InputProcessor");

            RecordingContinuation continuation = new RecordingContinuation();
            GeneratedInputProcessorDispatcher dispatcher = new GeneratedInputProcessorDispatcher(continuation);

            List<Object> rows = new ArrayList<>(List.of(new ArrayList<>(List.of("ab"))));
            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("rows", rows);

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    null,
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    List.of("rows"),
                    continuation.walkUnknownPaths,
                    "List<List<String>> has no element schema (the inner List is itself a container), "
                            + "so it must reach the reflective continuation rather than dispatching "
                            + "elements at List");
            assertSame(rows, out.get("rows"), "the continuation's return value is what lands in the output map");
        }

        @Test
        @DisplayName("Set<List<NestedTagDto>> does not dispatch elements at the inner container type")
        void nestedListInSetField_doesNotDispatchAtTheContainer() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), NESTED_TAG_DTO, NESTED_CONTAINER_DTO, NESTED_CONTAINER_RESOURCE);
            result.assertSuccess();

            result.assertGeneratedSourceDoesNotContain(
                    "com.example.rt.NestedContainerDto_InputProcessor", "dispatchObjectCollection(v, List");
        }

        @Test
        @DisplayName("a static field emits no arm — it is not a wire property")
        void staticField_emitsNoArm() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), NESTED_TAG_DTO, NESTED_CONTAINER_DTO, NESTED_CONTAINER_RESOURCE);
            result.assertSuccess();

            result.assertGeneratedSourceDoesNotContain(
                    "com.example.rt.NestedContainerDto_InputProcessor", "\"SHARED\"");
        }
    }

    // --- Wire-name projection ---

    /**
     * Proves that a <em>genuinely generated</em> {@code _InputProcessor} honors the traversal's
     * {@link InputFieldNameResolver} projection.
     *
     * <p>Every test here hands the processor a real parent {@link InputTraversalContext} seeded with
     * a non-identity projection ({@code user_name} → {@code userName}) and an intermediate keyed by
     * the wire names. A processor whose switch keys on the raw wire key matches no arm, so the
     * declared chain never runs and the value comes back untouched.
     */
    @Nested
    @DisplayName("wire-name projection in generated processors")
    class WireNameProjection {

        @Test
        @DisplayName("declared chain applies to a renamed field and the output keeps the wire key")
        @SuppressWarnings("unchecked")
        void renamedStringField_chainAppliedAndWireKeyKept() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), RENAMED_ADDRESS_DTO, RENAMED_PROFILE_DTO, RENAMED_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> processor =
                    newProcessor(result, "com.example.rt.RenamedProfileDto_InputProcessor");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("user_name", "  Ada Lovelace  ");

            Object output = processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, SNAKE_TO_CAMEL),
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertEquals(
                    "Ada Lovelace",
                    out.get("user_name"),
                    "@Canonicalize(TrimCanonicalizer) on userName must apply to the wire key user_name");
            assertFalse(
                    out.containsKey("userName"), "The projection selects metadata; it must not rename the wire key");
        }

        @Test
        @DisplayName("InputValueContext reports the wire path with the Java logical name")
        void renamedStringField_valueContextCarriesWirePathAndJavaLogicalName() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), RENAMED_ADDRESS_DTO, RENAMED_PROFILE_DTO, RENAMED_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> processor =
                    newProcessor(result, "com.example.rt.RenamedProfileDto_InputProcessor");

            List<InputValueContext> observed = new ArrayList<>();
            ChainResolver recording = (value, canonicalizers, sanitizers, ctx) -> {
                observed.add(ctx);
                return value;
            };

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("user_name", "  Ada Lovelace  ");

            processor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    recording,
                    GeneratedInputProcessorDispatcher.withoutContinuation(),
                    InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, SNAKE_TO_CAMEL),
                    "");

            assertEquals(1, observed.size(), "The renamed field's declared chain should have been applied once");
            InputValueContext valueCtx = observed.get(0);
            assertEquals("user_name", valueCtx.path(), "path is the wire path — it points at what the caller sent");
            assertEquals(
                    "userName",
                    valueCtx.logicalName(),
                    "logicalName is the Java property name once a property matched");
        }

        @Test
        @DisplayName("renamed nested DTO field dispatches, and the nested processor projects its own keys")
        @SuppressWarnings("unchecked")
        void renamedNestedDtoField_dispatchedAndNestedKeysProjected() throws Exception {
            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), RENAMED_ADDRESS_DTO, RENAMED_PROFILE_DTO, RENAMED_PROFILE_RESOURCE);
            result.assertSuccess();

            GeneratedInputProcessor<?> profileProcessor =
                    newProcessor(result, "com.example.rt.RenamedProfileDto_InputProcessor");
            GeneratedInputProcessor<?> addressProcessor =
                    newProcessor(result, "com.example.rt.RenamedAddressDto_InputProcessor");

            GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            registerProcessor(dispatcher, addressProcessor.targetType(), addressProcessor);

            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("city_name", "  Espoo  ");

            Map<String, Object> intermediate = new LinkedHashMap<>();
            intermediate.put("home_address", nested);

            Object output = profileProcessor.process(
                    intermediate,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    buildChainResolver(),
                    dispatcher,
                    InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, SNAKE_TO_CAMEL),
                    "");

            assertInstanceOf(Map.class, output);
            Map<String, Object> out = (Map<String, Object>) output;
            assertInstanceOf(Map.class, out.get("home_address"), "The nested map must keep its wire key");
            Map<String, Object> processedAddress = (Map<String, Object>) out.get("home_address");
            assertEquals(
                    "Espoo",
                    processedAddress.get("city_name"),
                    "RenamedAddressDto.cityName's @Canonicalize must apply under the city_name wire key");
        }
    }

    /**
     * Non-identity projection used by {@link WireNameProjection}: maps the fixtures' snake_case wire
     * keys onto their Java property names and returns every other key unchanged, honoring the
     * {@link InputFieldNameResolver} totality contract.
     */
    private static final InputFieldNameResolver SNAKE_TO_CAMEL = (ownerType, wireName) -> switch (wireName) {
        case "user_name" -> "userName";
        case "home_address" -> "homeAddress";
        case "city_name" -> "cityName";
        default -> wireName;
    };

    // --- Owner-set conformance fixtures (one matrix, compiled twice) ---

    /**
     * Leaf DTO reached from {@link #CONF_ROOT_DTO} through several container shapes. Carries its own
     * {@code @Sanitize} so the scanner emits {@code ConfLeafDto_InputProcessor}, which is what makes
     * it an owner on the generated path as well as the reflective one.
     */
    private static final JavaFileObject CONF_LEAF_DTO = SourceFiles.inline("com.example.conf.ConfLeafDto", """
            package com.example.conf;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class ConfLeafDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String text;
            }
            """);

    /** Target of the accessor-less field: reachable only through a {@code private} field with no accessor. */
    private static final JavaFileObject CONF_NESTED_DTO = SourceFiles.inline("com.example.conf.ConfNestedDto", """
            package com.example.conf;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class ConfNestedDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String label;
            }
            """);

    /**
     * Single-argument generic that is <em>not</em> a container. Both paths must classify a
     * {@code ConfWrapper<ConfLeafDto>} field to the erased {@code ConfWrapper} and stop — the type
     * argument is not reachable, which is issue #383's shape.
     */
    private static final JavaFileObject CONF_WRAPPER = SourceFiles.inline("com.example.conf.ConfWrapper", """
            package com.example.conf;
            public class ConfWrapper<T> {
                public T value;
            }
            """);

    /**
     * Multi-argument collection subtype binding its element to the <em>first</em> argument, so a
     * {@code ConfPair<ConfLeafDto, String>} field binds {@code ConfLeafDto} elements — the shape where
     * "type argument 0" happens to be the right answer.
     */
    private static final JavaFileObject CONF_PAIR = SourceFiles.inline("com.example.conf.ConfPair", """
            package com.example.conf;
            import java.util.ArrayList;
            public class ConfPair<A, B> extends ArrayList<A> {}
            """);

    /**
     * Collection subtype that <em>fixes</em> its element type, so a {@code ConfFixed<ConfLeafDto>}
     * field binds {@code String} elements and the declared argument is not the element type at all.
     */
    private static final JavaFileObject CONF_FIXED = SourceFiles.inline("com.example.conf.ConfFixed", """
            package com.example.conf;
            import java.util.ArrayList;
            public class ConfFixed<T> extends ArrayList<String> {}
            """);

    /**
     * Multi-argument collection subtype binding its element to the <em>second</em> argument, so a
     * {@code ConfWeird<String, ConfLeafDto>} field binds {@code ConfLeafDto} elements and "type
     * argument 0" is outright wrong.
     */
    private static final JavaFileObject CONF_WEIRD = SourceFiles.inline("com.example.conf.ConfWeird", """
            package com.example.conf;
            import java.util.ArrayList;
            public class ConfWeird<A, B> extends ArrayList<B> {}
            """);

    /**
     * The frozen conformance matrix, in one DTO: a non-container generic wrapper, an
     * {@code Optional}-wrapped DTO, the three collection-subtype shapes whose element is decided by
     * the {@code Collection<E>} supertype binding rather than by argument position, a nested
     * container, an accessor-less nested field, a container of maps, a self-reference, and a plain
     * {@code String} field — the last one being what makes a mismatch-family owner observable when
     * the wire shape disagrees with the declared shape.
     */
    private static final JavaFileObject CONF_ROOT_DTO = SourceFiles.inline("com.example.conf.ConfRootDto", """
            package com.example.conf;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            public class ConfRootDto {
                public ConfWrapper<ConfLeafDto> wrapped;
                public Optional<ConfLeafDto> optionalDto;
                public ConfPair<ConfLeafDto, String> pair;
                public ConfFixed<ConfLeafDto> fixed;
                public ConfWeird<String, ConfLeafDto> weird;
                public List<List<ConfLeafDto>> nestedLists;
                private ConfNestedDto accessorLess;
                public List<Map<String, ConfLeafDto>> mapsInList;
                public ConfRootDto self;
                @Sanitize(StripControlCharsSanitizer.class)
                public String name;
            }
            """);

    private static final JavaFileObject CONF_RESOURCE = SourceFiles.inline("com.example.conf.ConfResource", """
            package com.example.conf;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/conf")
            public class ConfResource {
                @POST
                public String create(ConfRootDto body) { return null; }
            }
            """);

    /** The compilation unit shared, byte-identical, by both conformance environments. */
    private static final JavaFileObject[] CONF_SOURCES = {
        CONF_LEAF_DTO, CONF_NESTED_DTO, CONF_WRAPPER, CONF_PAIR, CONF_FIXED, CONF_WEIRD, CONF_ROOT_DTO, CONF_RESOURCE
    };

    /** FQN of the conformance root DTO, loaded separately out of each environment's classloader. */
    private static final String CONF_ROOT_FQN = "com.example.conf.ConfRootDto";

    /** FQN of the generated companion whose presence distinguishes the two environments. */
    private static final String CONF_ROOT_PROCESSOR_FQN = "com.example.conf.ConfRootDto_InputProcessor";

    // --- Isolated Pair-shape conformance fixtures ---

    /**
     * The leaf of the isolated matrix. It is reachable from {@link #ISOLATED_ROOT_DTO} through the
     * {@code ConfPair}-shaped field and through <em>nothing else</em>, which is what makes the
     * containment assertion about that field's element rather than about a sibling's.
     */
    private static final JavaFileObject ISOLATED_LEAF_DTO = SourceFiles.inline("com.example.iso.IsoLeafDto", """
            package com.example.iso;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class IsoLeafDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String note;
            }
            """);

    /** The multi-argument collection subtype of the isolated matrix. */
    private static final JavaFileObject ISOLATED_PAIR = SourceFiles.inline("com.example.iso.IsoPair", """
            package com.example.iso;
            import java.util.ArrayList;
            public class IsoPair<A, B> extends ArrayList<A> {}
            """);

    /**
     * A root whose only route to {@link #ISOLATED_LEAF_DTO} is the {@code IsoPair}-shaped field. In
     * the main matrix the same leaf is also reachable through sibling fields, which masks a
     * divergence on the {@code Pair} shape behind their redundancy; here nothing masks it.
     */
    private static final JavaFileObject ISOLATED_ROOT_DTO = SourceFiles.inline("com.example.iso.IsoRootDto", """
            package com.example.iso;
            public class IsoRootDto {
                public IsoPair<IsoLeafDto, String> items;
            }
            """);

    private static final JavaFileObject ISOLATED_RESOURCE = SourceFiles.inline("com.example.iso.IsoResource", """
            package com.example.iso;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/iso")
            public class IsoResource {
                @POST
                public String create(IsoRootDto body) { return null; }
            }
            """);

    /** The isolated compilation unit, shared byte-identically by both environments. */
    private static final JavaFileObject[] ISOLATED_SOURCES = {
        ISOLATED_LEAF_DTO, ISOLATED_PAIR, ISOLATED_ROOT_DTO, ISOLATED_RESOURCE
    };

    /** FQN of the isolated root DTO. */
    private static final String ISOLATED_ROOT_FQN = "com.example.iso.IsoRootDto";

    /** FQN of the isolated root's generated companion. */
    private static final String ISOLATED_ROOT_PROCESSOR_FQN = "com.example.iso.IsoRootDto_InputProcessor";

    /** FQN of the leaf both paths must reach through the {@code IsoPair}-shaped field alone. */
    private static final String ISOLATED_LEAF_FQN = "com.example.iso.IsoLeafDto";

    // --- Isolated wildcard-bound-shape conformance fixtures ---

    /**
     * The leaf of the wildcard matrix, reachable from {@link #WILD_ROOT_DTO} through the
     * {@code WildOpt}-shaped field and through nothing else.
     */
    private static final JavaFileObject WILD_LEAF_DTO = SourceFiles.inline("com.example.wild.WildLeafDto", """
            package com.example.wild;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class WildLeafDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String note;
            }
            """);

    /**
     * A collection subtype whose element variable sits inside a <em>wildcard bound</em> nested in the
     * supertype's type argument. Resolving it needs the substitution to recurse through the wildcard;
     * a wildcard left unsubstituted keeps {@code T}, which normalizes to its {@code Object} bound and
     * loses the element entirely.
     */
    private static final JavaFileObject WILD_OPT = SourceFiles.inline("com.example.wild.WildOpt", """
            package com.example.wild;
            import java.util.ArrayList;
            import java.util.Optional;
            public class WildOpt<T> extends ArrayList<Optional<? extends T>> {}
            """);

    /** A root whose only route to {@link #WILD_LEAF_DTO} is the {@code WildOpt}-shaped field. */
    private static final JavaFileObject WILD_ROOT_DTO = SourceFiles.inline("com.example.wild.WildRootDto", """
            package com.example.wild;
            public class WildRootDto {
                public WildOpt<WildLeafDto> items;
            }
            """);

    private static final JavaFileObject WILD_RESOURCE = SourceFiles.inline("com.example.wild.WildResource", """
            package com.example.wild;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/wild")
            public class WildResource {
                @POST
                public String create(WildRootDto body) { return null; }
            }
            """);

    /** The wildcard compilation unit, shared byte-identically by both environments. */
    private static final JavaFileObject[] WILD_SOURCES = {WILD_LEAF_DTO, WILD_OPT, WILD_ROOT_DTO, WILD_RESOURCE};

    /** FQN of the wildcard root DTO. */
    private static final String WILD_ROOT_FQN = "com.example.wild.WildRootDto";

    /** FQN of the wildcard root's generated companion. */
    private static final String WILD_ROOT_PROCESSOR_FQN = "com.example.wild.WildRootDto_InputProcessor";

    /** FQN of the leaf both paths must reach through the {@code WildOpt}-shaped field alone. */
    private static final String WILD_LEAF_FQN = "com.example.wild.WildLeafDto";

    // --- Isolated non-generic-subtype-shape conformance fixtures ---

    /**
     * The leaf of the non-generic-subtype matrix, reachable from {@link #NONGEN_ROOT_DTO} through
     * the {@code NonGenDtos}-shaped field and through nothing else.
     */
    private static final JavaFileObject NONGEN_LEAF_DTO = SourceFiles.inline("com.example.ng.NonGenLeafDto", """
            package com.example.ng;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class NonGenLeafDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String note;
            }
            """);

    /**
     * A concrete, non-generic collection subtype whose element is fixed by its own declaration. A
     * field declared with this type is a plain {@code Class} use site — not a
     * {@code ParameterizedType} — carrying no local type argument at all, unlike {@link #CONF_FIXED}.
     */
    private static final JavaFileObject NONGEN_DTOS = SourceFiles.inline("com.example.ng.NonGenDtos", """
            package com.example.ng;
            import java.util.ArrayList;
            public final class NonGenDtos extends ArrayList<NonGenLeafDto> {}
            """);

    /** A root whose only route to {@link #NONGEN_LEAF_DTO} is the {@code NonGenDtos}-shaped field. */
    private static final JavaFileObject NONGEN_ROOT_DTO = SourceFiles.inline("com.example.ng.NonGenRootDto", """
            package com.example.ng;
            public class NonGenRootDto {
                public NonGenDtos items;
            }
            """);

    private static final JavaFileObject NONGEN_RESOURCE = SourceFiles.inline("com.example.ng.NonGenResource", """
            package com.example.ng;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/nongen")
            public class NonGenResource {
                @POST
                public String create(NonGenRootDto body) { return null; }
            }
            """);

    /** The non-generic-subtype compilation unit, shared byte-identically by both environments. */
    private static final JavaFileObject[] NONGEN_SOURCES = {
        NONGEN_LEAF_DTO, NONGEN_DTOS, NONGEN_ROOT_DTO, NONGEN_RESOURCE
    };

    /** FQN of the non-generic-subtype root DTO. */
    private static final String NONGEN_ROOT_FQN = "com.example.ng.NonGenRootDto";

    /** FQN of the non-generic-subtype root's generated companion. */
    private static final String NONGEN_ROOT_PROCESSOR_FQN = "com.example.ng.NonGenRootDto_InputProcessor";

    /** FQN of the leaf both paths must reach through the {@code NonGenDtos}-shaped field alone. */
    private static final String NONGEN_LEAF_FQN = "com.example.ng.NonGenLeafDto";

    // --- Isolated owner-bound-inner-class-shape conformance fixtures ---

    /**
     * The leaf of the owner-bound matrix, reachable from {@link #OWNERBOUND_ROOT_DTO} through the
     * {@code OwnerBoundOuter<T>.Inner}-shaped field and through nothing else.
     */
    private static final JavaFileObject OWNERBOUND_LEAF_DTO =
            SourceFiles.inline("com.example.ob.OwnerBoundLeafDto", """
            package com.example.ob;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            public class OwnerBoundLeafDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public String note;
            }
            """);

    /**
     * An outer/inner pair where the inner class's {@code Collection<E>} binding comes from the
     * enclosing instance's type argument rather than from any type argument local to {@code Inner}
     * itself — {@code Inner} declares no type parameters of its own.
     */
    private static final JavaFileObject OWNERBOUND_OUTER = SourceFiles.inline("com.example.ob.OwnerBoundOuter", """
            package com.example.ob;
            import java.util.ArrayList;
            public class OwnerBoundOuter<T> {
                public class Inner extends ArrayList<T> {}
            }
            """);

    /**
     * A root whose only route to {@link #OWNERBOUND_LEAF_DTO} is the
     * {@code OwnerBoundOuter<T>.Inner}-shaped field.
     */
    private static final JavaFileObject OWNERBOUND_ROOT_DTO =
            SourceFiles.inline("com.example.ob.OwnerBoundRootDto", """
            package com.example.ob;
            public class OwnerBoundRootDto {
                public OwnerBoundOuter<OwnerBoundLeafDto>.Inner items;
            }
            """);

    private static final JavaFileObject OWNERBOUND_RESOURCE =
            SourceFiles.inline("com.example.ob.OwnerBoundResource", """
            package com.example.ob;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/ownerbound")
            public class OwnerBoundResource {
                @POST
                public String create(OwnerBoundRootDto body) { return null; }
            }
            """);

    /** The owner-bound compilation unit, shared byte-identically by both environments. */
    private static final JavaFileObject[] OWNERBOUND_SOURCES = {
        OWNERBOUND_LEAF_DTO, OWNERBOUND_OUTER, OWNERBOUND_ROOT_DTO, OWNERBOUND_RESOURCE
    };

    /** FQN of the owner-bound root DTO. */
    private static final String OWNERBOUND_ROOT_FQN = "com.example.ob.OwnerBoundRootDto";

    /** FQN of the owner-bound root's generated companion. */
    private static final String OWNERBOUND_ROOT_PROCESSOR_FQN = "com.example.ob.OwnerBoundRootDto_InputProcessor";

    /**
     * FQN of the leaf both paths must reach through the {@code OwnerBoundOuter<T>.Inner}-shaped
     * field alone.
     */
    private static final String OWNERBOUND_LEAF_FQN = "com.example.ob.OwnerBoundLeafDto";

    // --- Owner-set conformance ---

    /**
     * Pins the invariant the empty-owner-set fallback rests on: the set of owner types the
     * <em>generated</em> path prepares is contained in the set the <em>reflective</em> path prepares,
     * over one fixture matrix compiled identically by both.
     *
     * <p><strong>Why two compilations.</strong> {@code GeneratedInputProcessorDispatcher} resolves a
     * companion through the target class's own classloader behind a {@code ClassValue}, so once real
     * APT output is loaded the public entry point selects the <em>generated</em> path for that
     * {@code Class}. A single compilation would therefore exercise the generated path twice and
     * report agreement no matter how far {@code AnnotationCollector} and {@code TypeClassifier} had
     * drifted — a false-positive proof of exactly the claim these tests exist to make. The identical
     * sources are compiled twice into two isolated harness classloaders: once with
     * {@link SanitizationProcessor}, once with a {@link NoOpAnnotationProcessor} so no companion
     * exists and the dispatcher necessarily falls through to reflection.
     * {@link #compileWithCodegen()} and {@link #compileWithoutCodegen()} each assert their own
     * environment's companion presence, so the discriminating mechanic cannot silently degrade.
     *
     * <p><strong>Containment, not equality.</strong> Equality is provably false: the reflective walk
     * records a field's raw declared class unconditionally, so {@code java.lang.String} is a genuine
     * owner there, while the generated path's {@code applyString} arm returns a shape-mismatched
     * value unchanged and never asks for an owner on the mismatch family.
     */
    @Nested
    @DisplayName("reflective ↔ generated owner-set conformance")
    class OwnerSetConformance {

        @Test
        @DisplayName("the generated path's owner set is contained in the reflective path's")
        void generatedOwnerSetIsContainedInTheReflectiveOne() throws Exception {
            Set<String> generated = prepareOwners(compileWithCodegen());
            Set<String> reflective = prepareOwners(compileWithoutCodegen());

            assertTrue(
                    reflective.containsAll(generated),
                    () -> ("The generated path prepares an owner the reflective path does not, so"
                                    + " AnnotationCollector and TypeClassifier have diverged."
                                    + "\n  generated only: %s\n  generated:      %s\n  reflective:     %s")
                            .formatted(difference(generated, reflective), generated, reflective));

            // Non-vacuity: containment over an empty generated set would prove nothing, and the two
            // sets being equal would mean the two environments never diverged — which is what a
            // broken (single-compilation) mechanic looks like.
            assertFalse(generated.isEmpty(), "the generated path must prepare at least its own root");
            assertEquals(
                    Set.of(
                            "java.lang.String",
                            "com.example.conf.ConfPair",
                            "com.example.conf.ConfFixed",
                            "com.example.conf.ConfWeird"),
                    difference(reflective, generated),
                    "the reflective path's surplus is exactly the mismatch family: a field's raw declared"
                            + " class is recorded unconditionally — the String field's, and each collection"
                            + " subtype's container class — while the generated path's arms dispatch against"
                            + " the element type or the origin class and never ask for an owner on either");
        }

        @Test
        @DisplayName("containment holds on a root whose only route to its leaf is the Pair-shaped field")
        void generatedOwnerSetIsContainedInTheReflectiveOneForAnIsolatedPairShape() throws Exception {
            Set<String> generated =
                    prepareOwners(compileWithCodegen(ISOLATED_SOURCES, ISOLATED_ROOT_PROCESSOR_FQN), ISOLATED_ROOT_FQN);
            Set<String> reflective = prepareOwners(
                    compileWithoutCodegen(ISOLATED_SOURCES, ISOLATED_ROOT_PROCESSOR_FQN), ISOLATED_ROOT_FQN);

            assertTrue(
                    reflective.containsAll(generated),
                    () -> ("The generated path prepares an owner the reflective path does not on a shape no"
                                    + " sibling field can mask, so AnnotationCollector and TypeClassifier have"
                                    + " diverged on the collection element rule."
                                    + "\n  generated only: %s\n  generated:      %s\n  reflective:     %s")
                            .formatted(difference(generated, reflective), generated, reflective));
            assertTrue(
                    generated.contains(ISOLATED_LEAF_FQN),
                    () -> "the generated path dispatches the Pair-shaped field's elements against the leaf, so"
                            + " it must declare it as an owner; generated=" + generated);
            assertTrue(
                    reflective.contains(ISOLATED_LEAF_FQN),
                    () -> "the reflective path binds the same element through the Collection<E> supertype"
                            + " binding, so it must prepare the leaf too; reflective=" + reflective);
        }

        @Test
        @DisplayName("containment holds on a root whose only route to its leaf is a wildcard-bound element")
        void generatedOwnerSetIsContainedInTheReflectiveOneForAnIsolatedWildcardShape() throws Exception {
            Set<String> generated =
                    prepareOwners(compileWithCodegen(WILD_SOURCES, WILD_ROOT_PROCESSOR_FQN), WILD_ROOT_FQN);
            Set<String> reflective =
                    prepareOwners(compileWithoutCodegen(WILD_SOURCES, WILD_ROOT_PROCESSOR_FQN), WILD_ROOT_FQN);

            assertTrue(
                    reflective.containsAll(generated),
                    () -> ("The generated path prepares an owner the reflective path does not on a"
                                    + " WildOpt<T> extends ArrayList<Optional<? extends T>> shape, so"
                                    + " AnnotationCollector substitutes into a wildcard bound where"
                                    + " TypeClassifier does not."
                                    + "\n  generated only: %s\n  generated:      %s\n  reflective:     %s")
                            .formatted(difference(generated, reflective), generated, reflective));
            assertTrue(
                    generated.contains(WILD_LEAF_FQN),
                    () -> "the generated path unwraps the wildcard bound after substitution, so it dispatches"
                            + " the field's elements against the leaf and must declare it as an owner;"
                            + " generated=" + generated);
            assertTrue(
                    reflective.contains(WILD_LEAF_FQN),
                    () -> "the reflective path must substitute the binding into the wildcard's bound too, or"
                            + " it prepares no owner for an element the generated path dispatches against;"
                            + " reflective=" + reflective);
        }

        @Test
        @DisplayName("containment holds on a root whose only route to its leaf is a non-generic collection subtype")
        void generatedOwnerSetIsContainedInTheReflectiveOneForAnIsolatedNonGenericSubtypeShape() throws Exception {
            Set<String> generated =
                    prepareOwners(compileWithCodegen(NONGEN_SOURCES, NONGEN_ROOT_PROCESSOR_FQN), NONGEN_ROOT_FQN);
            Set<String> reflective =
                    prepareOwners(compileWithoutCodegen(NONGEN_SOURCES, NONGEN_ROOT_PROCESSOR_FQN), NONGEN_ROOT_FQN);

            assertTrue(
                    reflective.containsAll(generated),
                    () -> ("The generated path prepares an owner the reflective path does not on a"
                                    + " NonGenDtos extends ArrayList<NonGenLeafDto> shape — a plain Class use"
                                    + " site with no local type argument — so AnnotationCollector and"
                                    + " TypeClassifier have diverged on the raw-class-alone gate."
                                    + "\n  generated only: %s\n  generated:      %s\n  reflective:     %s")
                            .formatted(difference(generated, reflective), generated, reflective));
            assertTrue(
                    generated.contains(NONGEN_LEAF_FQN),
                    () -> "the generated path dispatches the NonGenDtos-shaped field's elements against the"
                            + " leaf, so it must declare it as an owner; generated=" + generated);
            assertTrue(
                    reflective.contains(NONGEN_LEAF_FQN),
                    () -> "the reflective path binds the same element from the class's own generic superclass,"
                            + " so it must prepare the leaf too, even though the field's own use site carries no"
                            + " type argument; reflective=" + reflective);
        }

        @Test
        @DisplayName("containment holds on a root whose only route to its leaf is an owner-bound inner class")
        void generatedOwnerSetIsContainedInTheReflectiveOneForAnIsolatedOwnerBoundInnerClassShape() throws Exception {
            Set<String> generated = prepareOwners(
                    compileWithCodegen(OWNERBOUND_SOURCES, OWNERBOUND_ROOT_PROCESSOR_FQN), OWNERBOUND_ROOT_FQN);
            Set<String> reflective = prepareOwners(
                    compileWithoutCodegen(OWNERBOUND_SOURCES, OWNERBOUND_ROOT_PROCESSOR_FQN), OWNERBOUND_ROOT_FQN);

            assertTrue(
                    reflective.containsAll(generated),
                    () -> ("The generated path prepares an owner the reflective path does not on an"
                                    + " OwnerBoundOuter<T> { class Inner extends ArrayList<T> {} } shape used as"
                                    + " OwnerBoundOuter<Leaf>.Inner, so AnnotationCollector and TypeClassifier"
                                    + " have diverged on resolving the element through the parameterized owner"
                                    + " type."
                                    + "\n  generated only: %s\n  generated:      %s\n  reflective:     %s")
                            .formatted(difference(generated, reflective), generated, reflective));
            assertTrue(
                    generated.contains(OWNERBOUND_LEAF_FQN),
                    () -> "the generated path dispatches the owner-bound field's elements against the leaf, so"
                            + " it must declare it as an owner; generated=" + generated);
            assertTrue(
                    reflective.contains(OWNERBOUND_LEAF_FQN),
                    () -> "the reflective path binds the same element through the enclosing instance's type"
                            + " argument, so it must prepare the leaf too, even though Inner carries no local"
                            + " type argument of its own; reflective=" + reflective);
        }

        @Test
        @DisplayName("every owner observed on the reflective path was prepared before traversal")
        void everyObservedOwnerWasPreparedOnTheReflectivePath() throws Exception {
            RecordingNameResolver resolver = traverse(compileWithoutCodegen());

            assertTrue(resolver.prepared().containsAll(resolver.observed()), () -> "observed but never prepared: %s"
                    .formatted(difference(resolver.observed(), resolver.prepared())));
            assertTrue(
                    resolver.observed().contains("java.lang.String"),
                    () -> "the deliberately mismatched {\"name\": {\"x\": 1}} payload must dispatch against the"
                            + " String field's raw declared class, or this assertion is vacuous; observed="
                            + resolver.observed());
        }

        @Test
        @DisplayName("every owner observed on the generated path was prepared before traversal")
        void everyObservedOwnerWasPreparedOnTheGeneratedPath() throws Exception {
            RecordingNameResolver resolver = traverse(compileWithCodegen());

            assertTrue(resolver.prepared().containsAll(resolver.observed()), () -> "observed but never prepared: %s"
                    .formatted(difference(resolver.observed(), resolver.prepared())));
            assertTrue(
                    resolver.observed().contains("com.example.conf.ConfWrapper"),
                    () -> "the generated root must hand its non-container generic field to the reflective"
                            + " continuation, or the codegen↔reflection boundary is untested; observed="
                            + resolver.observed());
            assertFalse(
                    resolver.observed().contains("java.lang.String"),
                    () -> "the generated path returns a shape-mismatched String value unchanged, so it must"
                            + " never ask for an owner on the mismatch family; observed=" + resolver.observed());
        }
    }

    // --- Owner-set conformance helpers ---

    /**
     * Compiles the conformance matrix with the real {@link SanitizationProcessor} and asserts the
     * root's generated companion is present, so the dispatcher takes the generated path for classes
     * loaded from this result's classloader.
     *
     * @return the codegen-active compilation result
     */
    private static Result compileWithCodegen() {
        return compileWithCodegen(CONF_SOURCES, CONF_ROOT_PROCESSOR_FQN);
    }

    /**
     * Compiles {@code sources} with the real {@link SanitizationProcessor} and asserts the named
     * companion is present, so the dispatcher takes the generated path for classes loaded from this
     * result's classloader.
     *
     * @param sources      the compilation unit
     * @param processorFqn the generated companion whose presence discriminates this environment
     * @return the codegen-active compilation result
     */
    private static Result compileWithCodegen(JavaFileObject[] sources, String processorFqn) {
        Result result = ProcessorTestHarness.run(new SanitizationProcessor(), sources);
        result.assertSuccess();
        assertNotNull(
                result.loadGeneratedClass(processorFqn), "the codegen environment must carry the generated companion");
        return result;
    }

    /**
     * Compiles the same sources with a no-op processor and asserts no generated companion exists, so
     * the dispatcher necessarily falls through to the reflective walk for classes loaded from this
     * result's classloader.
     *
     * @return the codegen-inactive compilation result
     */
    private static Result compileWithoutCodegen() {
        return compileWithoutCodegen(CONF_SOURCES, CONF_ROOT_PROCESSOR_FQN);
    }

    /**
     * Compiles {@code sources} with a no-op processor and asserts the named companion does not exist,
     * so the dispatcher necessarily falls through to the reflective walk for classes loaded from this
     * result's classloader.
     *
     * @param sources      the compilation unit
     * @param processorFqn the generated companion that must be absent here
     * @return the codegen-inactive compilation result
     */
    private static Result compileWithoutCodegen(JavaFileObject[] sources, String processorFqn) {
        Result result = ProcessorTestHarness.run(new NoOpAnnotationProcessor(), sources);
        result.assertSuccess();
        assertThrows(
                AssertionFailedError.class,
                () -> result.loadGeneratedClass(processorFqn),
                "the reflective environment must carry no generated companion — otherwise both"
                        + " environments exercise the generated path and the comparison is vacuous");
        return result;
    }

    /**
     * Runs the public precompute entry point over the conformance root loaded from the given
     * environment's classloader and returns the prepared owners by binary name.
     *
     * <p>Names, not {@code Class} instances: the two environments load the same fixture sources
     * through different classloaders, so their {@code Class} objects are never {@code equals}.
     *
     * @param result the compilation environment
     * @return the prepared owner binary names, in preparation order
     * @throws Exception if the root fixture class cannot be loaded
     */
    private static Set<String> prepareOwners(Result result) throws Exception {
        return prepareOwners(result, CONF_ROOT_FQN);
    }

    /**
     * Runs the public precompute entry point over the named root loaded from the given environment's
     * classloader and returns the prepared owners by binary name.
     *
     * @param result  the compilation environment
     * @param rootFqn the root DTO to precompute
     * @return the prepared owner binary names, in preparation order
     * @throws Exception if the root fixture class cannot be loaded
     */
    private static Set<String> prepareOwners(Result result, String rootFqn) throws Exception {
        RecordingNameResolver resolver = new RecordingNameResolver();
        newConformanceEngine().precomputeFieldNameResolution(result.loadGeneratedClass(rootFqn), resolver);
        return resolver.prepared();
    }

    /**
     * Precomputes, seals the resolver, then drives a real traversal of the mismatch-carrying payload
     * through the public {@code processInput} entry point. The resolver throws on any
     * {@code logicalName} for an owner that was not prepared, so the postcondition is enforced at the
     * call site rather than only asserted afterwards.
     *
     * @param result the compilation environment
     * @return the resolver, carrying both the prepared and the observed owner names
     * @throws Exception if the root fixture class cannot be loaded
     */
    private static RecordingNameResolver traverse(Result result) throws Exception {
        Class<?> root = result.loadGeneratedClass(CONF_ROOT_FQN);
        RecordingNameResolver resolver = new RecordingNameResolver();
        InputObjectProcessor engine = newConformanceEngine();
        engine.precomputeFieldNameResolution(root, resolver);
        resolver.seal();
        engine.processInput(conformancePayload(), root, EffectiveInputPolicies.NONE, InputLocation.BODY, resolver);
        return resolver;
    }

    /**
     * Builds a default engine whose canonicalizer and sanitizer factories instantiate the built-in
     * processors reflectively.
     *
     * @return a fresh engine; each environment gets its own so their dispatcher caches never mix
     */
    private static InputObjectProcessor newConformanceEngine() {
        return InputObjectProcessor.createDefault(
                cls -> (Canonicalizer) instantiate(cls), cls -> (Sanitizer) instantiate(cls));
    }

    /**
     * Instantiates a chain processor class through its public no-arg constructor.
     *
     * @param cls the processor class
     * @return the new instance
     */
    private static Object instantiate(Class<?> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + cls.getName(), e);
        }
    }

    /**
     * Builds the wire payload that reaches every shape in the conformance matrix, including the
     * deliberate {@code {"name": {"x": 1}}} mismatch against the {@code String name} field. That
     * mismatch is the only way a mismatch-family owner becomes observable at all —
     * {@code logicalFieldName} is called from one site, inside {@code processMap}.
     *
     * @return a mutable, insertion-ordered intermediate
     */
    private static Map<String, Object> conformancePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("wrapped", wireMap("value", " wrapped "));
        payload.put("optionalDto", wireMap("text", " optional "));
        payload.put("pair", wireList(wireMap("text", " pair ")));
        payload.put("fixed", wireList(" fixed "));
        payload.put("weird", wireList(wireMap("text", " weird ")));
        payload.put("nestedLists", wireList(wireList(wireMap("text", " nested "))));
        payload.put("accessorLess", wireMap("label", " accessor-less "));
        payload.put("mapsInList", wireList(wireMap("anyKey", wireMap("text", " in map "))));
        payload.put("self", wireMap("name", " self "));
        payload.put("name", wireMap("x", 1));
        return payload;
    }

    /**
     * Builds a single-entry mutable map, mirroring a one-key JSON object fragment.
     *
     * @param key   the wire key
     * @param value the wire value
     * @return a new mutable map
     */
    private static Map<String, Object> wireMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Builds a single-element mutable list, mirroring a one-element JSON array fragment.
     *
     * @param element the sole element
     * @return a new mutable list
     */
    private static List<Object> wireList(Object element) {
        List<Object> list = new ArrayList<>();
        list.add(element);
        return list;
    }

    /**
     * Returns the elements of {@code left} that {@code right} does not contain, for failure messages
     * that name the divergence rather than dumping two sets and leaving the reader to diff them.
     *
     * @param left  the set to subtract from
     * @param right the set to subtract
     * @return a new set holding {@code left \ right}
     */
    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /**
     * Tracing {@link InputFieldNameResolver} that records every owner handed to {@code precompute}
     * and every owner handed to {@code logicalName}, keyed by binary class name so owners loaded by
     * two different harness classloaders compare.
     *
     * <p>Once {@link #seal()} has been called, an unprepared owner reaching {@code logicalName} —
     * or a late {@code precompute} — throws. Both are contract violations of
     * {@code precomputeFieldNameResolution}'s postcondition, and failing at the call site names the
     * offending owner instead of leaving a set difference to be interpreted afterwards.
     */
    private static final class RecordingNameResolver implements InputFieldNameResolver {

        private final Set<String> prepared = new LinkedHashSet<>();
        private final Set<String> observed = new LinkedHashSet<>();
        private boolean sealed;

        @Override
        public void precompute(Class<?> ownerType) {
            if (sealed) {
                throw new AssertionFailedError("precompute(" + ownerType.getName()
                        + ") arrived after precomputeFieldNameResolution returned; the postcondition is that"
                        + " every statically knowable owner is prepared before it returns");
            }
            prepared.add(ownerType.getName());
        }

        @Override
        public String logicalName(Class<?> ownerType, String wireName) {
            String owner = ownerType.getName();
            observed.add(owner);
            if (sealed && !prepared.contains(owner)) {
                throw new AssertionFailedError("Owner " + owner + " reached logicalName(\"" + wireName
                        + "\") without having been precomputed; prepared=" + prepared);
            }
            return wireName;
        }

        /** Marks preparation complete, after which an unprepared owner is a failure rather than a record. */
        void seal() {
            sealed = true;
        }

        /**
         * Returns the owners handed to {@code precompute}, by binary name.
         *
         * @return the prepared owner names in preparation order
         */
        Set<String> prepared() {
            return prepared;
        }

        /**
         * Returns the owners handed to {@code logicalName}, by binary name.
         *
         * @return the observed owner names in observation order
         */
        Set<String> observed() {
            return observed;
        }
    }

    /**
     * Annotation processor that claims every annotation and does nothing, so the conformance matrix
     * can be compiled through the same harness with codegen switched off. Passing an explicit
     * processor also suppresses javac's service-loader discovery, which would otherwise pick
     * {@link SanitizationProcessor} back up off the test classpath.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class NoOpAnnotationProcessor extends AbstractProcessor {

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return false;
        }
    }

    // --- Helper: chain resolver using real built-in processors ---

    /**
     * Builds a {@link ChainResolver} that instantiates built-in canonicalizers and sanitizers
     * via reflection (they all have public no-arg constructors).
     *
     * @return a chain resolver backed by reflective processor instantiation
     */
    private static ChainResolver buildChainResolver() {
        return (value, canonicalizers, sanitizers, ctx) -> {
            String result = value;
            for (Class<? extends dev.vertique.core.sanitization.Canonicalizer> cls : canonicalizers) {
                try {
                    dev.vertique.core.sanitization.Canonicalizer c =
                            cls.getDeclaredConstructor().newInstance();
                    result = c.canonicalize(result, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate canonicalizer " + cls.getName(), e);
                }
            }
            for (Class<? extends dev.vertique.core.sanitization.Sanitizer> cls : sanitizers) {
                try {
                    dev.vertique.core.sanitization.Sanitizer s =
                            cls.getDeclaredConstructor().newInstance();
                    result = s.sanitize(result, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate sanitizer " + cls.getName(), e);
                }
            }
            return result;
        };
    }

    /**
     * Loads a generated processor class from the harness classloader and instantiates it via its
     * public no-arg constructor.
     *
     * @param result       the compilation result holding the harness classloader
     * @param generatedFqn the fully-qualified name of the generated {@code _InputProcessor} class
     * @return a freshly constructed processor instance
     * @throws Exception if the class cannot be loaded or instantiated
     */
    private static GeneratedInputProcessor<?> newProcessor(Result result, String generatedFqn) throws Exception {
        Class<?> processorClass = result.loadGeneratedClass(generatedFqn);
        return (GeneratedInputProcessor<?>)
                processorClass.getDeclaredConstructor().newInstance();
    }

    /**
     * Registers a generated processor for a DTO class in the dispatcher using reflection to
     * work around the {@code Class<T>} type bound that prevents direct generic assignment across
     * classloader boundaries.
     *
     * @param dispatcher the dispatcher to register with
     * @param dtoClass   the DTO class to register for
     * @param processor  the processor instance to register
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerProcessor(
            GeneratedInputProcessorDispatcher dispatcher, Class<?> dtoClass, GeneratedInputProcessor<?> processor) {
        // Use raw types to bypass the type bound on register(Class<T>, GeneratedInputProcessor<T>)
        ((GeneratedInputProcessorDispatcher) dispatcher)
                .register((Class) dtoClass, (GeneratedInputProcessor) processor);
    }
}
