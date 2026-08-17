// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.input.processing.ChainResolver;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.GeneratedInputProcessor;
import dev.vertique.input.processing.GeneratedInputProcessorDispatcher;
import dev.vertique.input.processing.InputFieldNameResolver;
import dev.vertique.input.processing.InputTraversalContext;
import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
