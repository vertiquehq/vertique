// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.rest.core.request.ChainResolver;
import dev.vertique.rest.core.request.EffectiveInputPolicies;
import dev.vertique.rest.core.request.GeneratedInputProcessor;
import dev.vertique.rest.core.request.GeneratedInputProcessorDispatcher;
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
                                dev.vertique.rest.core.request.InputTraversalContext ctx,
                                InputLocation location,
                                String fieldPath,
                                Class<?> ownerType) {
                            throw new UnsupportedOperationException("Reflective fallback not expected");
                        }

                        @Override
                        public Object walkUnknown(
                                Object inter,
                                dev.vertique.rest.core.request.InputTraversalContext ctx,
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
