// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.integration.ContextUtils;
import io.swagger.v3.oas.integration.GenericOpenApiContext;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.integration.api.OpenAPIConfiguration;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Composition tests for this module's {@link ModelConverter} implementations: they pin the schema
 * shape {@link BigDecimalModelConverter} and {@link ScalarOptionalModelConverter} produce
 * <em>together</em> for record DTOs, which no single-converter unit test can prove.
 *
 * <p>{@link FutureModelConverter} takes no part in those DTO assertions: none of the fixtures below
 * has a {@code Future}-typed component, so nothing in them would notice its absence. It is
 * exercised by {@link RegistrationOrder} instead, which resolves {@code Future<BigDecimal>} and
 * {@code Future<ReadStream<SseEvent>>} through the same chain and through Swagger's configuration
 * deep-copy path.
 *
 * <p>Resolution is driven through an explicit, per-test {@link ModelConverterContextImpl} chain —
 * never {@link ModelConverters#getInstance()}. The static singleton is process-wide mutable state
 * shared by every test and by any Swagger tooling running in the same JVM, so a test built on it
 * would be order-dependent and would race under parallel builds. The explicit chain makes each
 * assertion a deterministic statement about a known converter sequence.
 *
 * <p>These assertions previously lived in {@code vertique-example-hello} as
 * {@code OpenApiSchemaAssertionsTest}, where they read the {@code openapi.json} that example's
 * Maven build generated. That made an example module the proving ground for framework behaviour and
 * bound the proof to a build artifact produced through the same static singleton. They are stated
 * here, against the converters themselves, instead.
 *
 * <p><strong>What each converter is load-bearing for</strong> (measured by removing it from
 * {@link #chainConverters()} and observing which assertions turn red): {@link
 * BigDecimalModelConverter} carries {@code amount} and {@code discount};
 * {@link ScalarOptionalModelConverter} carries {@code rank} <em>only</em>. The {@code nickname} and
 * {@code tags} assertions stay green without it, because swagger-core's {@link ModelResolver}
 * unwraps generic {@code Optional<T>} natively — they pin that native behaviour, not this module's.
 * {@link FutureModelConverter} carries no DTO assertion at all; its removal reddens
 * {@link RegistrationOrder#futureOfBigDecimal_resolvesAsDecimalStringBecauseFutureRestartsResolution()},
 * where the un-unwrapped {@code Future} reaches the {@link ModelResolver} and resolves as a
 * JavaBean object schema with a {@code complete} property instead of the decimal-string schema.
 *
 * <p>The fixtures below mirror the <em>shapes</em> of that example's {@code OptionalGreeting} and
 * {@code PriceQuote} DTOs. They are fixtures, not copies: they exist to exercise the converters,
 * and they may drift from the example without that being a defect.
 */
class OpenApiSchemaCompositionTest {

    /** The anchored plain-decimal pattern {@link BigDecimalModelConverter} emits. */
    private static final String DECIMAL_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$";

    // --- Fixtures ---

    /**
     * Price-quote-shaped fixture: a required {@code sku}, a required {@link BigDecimal}
     * {@code amount}, and an unmarked {@code Optional<BigDecimal> discount}.
     *
     * @param sku the product SKU
     * @param amount the price amount
     * @param discount an optional discount amount
     */
    record QuoteFixture(
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = RequiredMode.REQUIRED)
            String sku,

            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = RequiredMode.REQUIRED)
            BigDecimal amount,
            // Deliberately unmarked: discount is the optional decimal property under test.
            Optional<BigDecimal> discount) {}

    /**
     * Optional-greeting-shaped fixture: a required {@code name} plus three unmarked optional
     * properties covering generic {@link Optional}, {@link Optional} of a collection, and the
     * non-generic {@link OptionalInt}.
     *
     * @param name the greeted name
     * @param nickname an optional nickname
     * @param tags an optional list of tags
     * @param rank an optional numeric rank
     */
    record GreetingFixture(
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = RequiredMode.REQUIRED)
            String name,
            // Deliberately unmarked: these three are the optional-unwrapping properties under test.
            Optional<String> nickname,
            Optional<List<String>> tags,
            OptionalInt rank) {}

    // --- Converter chain ---

    /**
     * Builds the converter chain used for the DTO composition fixtures: optional/decimal converters
     * ahead of {@link FutureModelConverter}, then a {@link ModelResolver} tail so record types
     * resolve to object schemas at all (without a resolver at the tail the chain exhausts and
     * returns {@code null}).
     *
     * <p>{@link FutureModelConverter} restarts resolution after unwrap, so this order is a
     * convenient fixture rather than a required registration sequence. Swagger's plugin path does
     * not preserve {@code <modelConverterClasses>} order (Jackson deep-copy into a {@code HashSet})
     * — see {@link RegistrationOrder}.
     *
     * @return the converters, head first
     */
    private static List<ModelConverter> chainConverters() {
        return List.of(
                new ScalarOptionalModelConverter(),
                new BigDecimalModelConverter(),
                new FutureModelConverter(),
                new ModelResolver(Json.mapper()));
    }

    // --- Optional unwrapping ---

    @Nested
    @DisplayName("JDK optional unwrapping")
    class OptionalUnwrapping {

        @Test
        @DisplayName("nickname is a plain string schema, not an object with present/empty, and is not required")
        void nickname_isUnwrappedPlainString_notRequired() {
            Schema<?> greeting = resolveModel(GreetingFixture.class);
            Schema<?> nickname = property(greeting, "nickname");

            assertEquals(
                    "string",
                    nickname.getType(),
                    "nickname must resolve to a plain string schema (Optional<String> unwrapped), not an object"
                            + " schema wrapping present/empty");
            assertNoOptionalWrapperProperties(nickname, "nickname");
            assertFalse(isRequired(greeting, "nickname"), "nickname must not appear in the required list");
            // "omit, don't null": an empty Optional is omitted from the payload, never written as JSON
            // null, so the schema stays non-nullable and a spec-validating client rejects an explicit null.
            assertFalse(Boolean.TRUE.equals(nickname.getNullable()), "nickname schema must not be nullable");
        }

        @Test
        @DisplayName("tags is an array-of-string schema (Optional<List<String>> unwrapped)")
        void tags_isUnwrappedArrayOfString() {
            Schema<?> greeting = resolveModel(GreetingFixture.class);
            Schema<?> tags = property(greeting, "tags");

            assertEquals("array", tags.getType(), "tags must resolve to an array schema");
            assertNotNull(tags.getItems(), "tags array schema must carry an items schema");
            assertEquals(
                    "string", tags.getItems().getType(), "tags array elements must resolve to a plain string schema");
            assertFalse(isRequired(greeting, "tags"), "tags must not appear in the required list");
        }

        @Test
        @DisplayName("rank is a scalar integer/number schema (OptionalInt), not a present/empty bean")
        void rank_isScalarIntegerSchema() {
            Schema<?> greeting = resolveModel(GreetingFixture.class);
            Schema<?> rank = property(greeting, "rank");

            String type = rank.getType();
            assertTrue(
                    "integer".equals(type) || "number".equals(type),
                    "rank must resolve to a scalar integer/number schema (ScalarOptionalModelConverter), but was: "
                            + rank);
            assertNoOptionalWrapperProperties(rank, "rank");
            assertFalse(isRequired(greeting, "rank"), "rank must not appear in the required list");
        }
    }

    // --- BigDecimal string wire form ---

    @Nested
    @DisplayName("BigDecimal decimal-string wire form")
    class DecimalStringWireForm {

        @Test
        @DisplayName("amount is the vertique-strict decimal-string schema")
        void amount_isDecimalStringSchema() {
            Schema<?> quote = resolveModel(QuoteFixture.class);

            assertDecimalStringSchema(property(quote, "amount"), "amount");
        }

        @Test
        @DisplayName("discount is the vertique-strict decimal-string schema (Optional<BigDecimal> unwrapped)")
        void discount_isUnwrappedDecimalStringSchema() {
            Schema<?> quote = resolveModel(QuoteFixture.class);

            assertDecimalStringSchema(property(quote, "discount"), "discount");
            assertFalse(isRequired(quote, "discount"), "discount must not appear in the required list");
        }

        @Test
        @DisplayName("the greeting-shaped fixture has no string/format=decimal property (negative control)")
        void greetingFixture_hasNoDecimalFormatProperty() {
            Schema<?> greeting = resolveModel(GreetingFixture.class);
            Map<String, Schema> properties = greeting.getProperties();
            assertNotNull(properties, "the greeting-shaped fixture must resolve to a schema with properties");

            properties.forEach((name, propertySchema) -> {
                boolean isDecimalFormatted =
                        "string".equals(propertySchema.getType()) && "decimal".equals(propertySchema.getFormat());
                assertFalse(
                        isDecimalFormatted,
                        "property '" + name + "' must not be a decimal-format string schema (no BigDecimal here)");
            });
        }
    }

    // --- Required marking ---

    @Nested
    @DisplayName("required marking")
    class RequiredMarking {

        @Test
        @DisplayName("the quote-shaped fixture's required list contains sku and amount")
        void quoteFixture_requiredContainsSkuAndAmount() {
            Schema<?> quote = resolveModel(QuoteFixture.class);

            assertTrue(isRequired(quote, "sku"), "sku must appear in the required list");
            assertTrue(isRequired(quote, "amount"), "amount must appear in the required list");
        }

        @Test
        @DisplayName("the greeting-shaped fixture's required list contains only name")
        void greetingFixture_requiredContainsOnlyName() {
            Schema<?> greeting = resolveModel(GreetingFixture.class);

            assertTrue(isRequired(greeting, "name"), "name must appear in the required list");
            assertFalse(isRequired(greeting, "nickname"), "nickname must not appear in the required list");
            assertFalse(isRequired(greeting, "tags"), "tags must not appear in the required list");
            assertFalse(isRequired(greeting, "rank"), "rank must not appear in the required list");
        }
    }

    // --- Registration order / plugin configuration path ---

    @Nested
    @DisplayName("swagger-maven-plugin registration and Future composition")
    class RegistrationOrder {

        /**
         * Pins what {@link ModelConverters#addConverter} does when called sequentially. A throwaway
         * {@code new ModelConverters()} is used rather than {@link ModelConverters#getInstance()} so
         * this test mutates no process-wide singleton. The {@link ModelResolver} a fresh registry
         * seeds still registers Jackson modules on {@link Json#mapper()}; Jackson dedupes by module
         * type id, so that registration is idempotent and harmless.
         *
         * <p>This is <em>not</em> the order the swagger-maven-plugin preserves from XML: see
         * {@link #deepCopy_deserializesModelConverterClassesIntoHashSet()}.
         */
        @Test
        @DisplayName("addConverter prepends, so sequential registrations reverse relative to call order")
        void addConverter_prependsSoDeclaredOrderIsReversed() {
            ModelConverters registry = new ModelConverters();
            FutureModelConverter future = new FutureModelConverter();
            BigDecimalModelConverter bigDecimal = new BigDecimalModelConverter();
            ScalarOptionalModelConverter scalarOptional = new ScalarOptionalModelConverter();

            registry.addConverter(future);
            registry.addConverter(bigDecimal);
            registry.addConverter(scalarOptional);

            List<ModelConverter> converters = registry.getConverters();

            assertEquals(
                    4,
                    converters.size(),
                    "a fresh ModelConverters seeds one ModelResolver, so three registrations must yield four"
                            + " converters, but was: " + converters);
            assertSame(scalarOptional, converters.get(0), "the last-registered converter must be at the chain head");
            assertSame(bigDecimal, converters.get(1), "the second-registered converter must be second in the chain");
            assertSame(future, converters.get(2), "the first-registered converter must be last of the three");
            assertInstanceOf(
                    ModelResolver.class, converters.get(3), "the seeded ModelResolver must remain at the chain tail");
        }

        /**
         * Pins the Swagger configuration-copy step that makes pom declaration order unreliable:
         * {@link ContextUtils#deepCopy} JSON-round-trips {@link SwaggerConfiguration} and
         * deserializes {@code modelConverterClasses} into a plain {@link HashSet}.
         */
        @Test
        @DisplayName("ContextUtils.deepCopy deserializes modelConverterClasses into a HashSet")
        void deepCopy_deserializesModelConverterClassesIntoHashSet() {
            LinkedHashSet<String> declared = new LinkedHashSet<>(
                    List.of(SseModelConverter.class.getName(), FutureModelConverter.class.getName()));
            SwaggerConfiguration config = new SwaggerConfiguration().modelConverterClasses(declared);

            OpenAPIConfiguration copied = ContextUtils.deepCopy(config);

            assertNotNull(copied, "deepCopy must return a configuration");
            assertNotNull(copied.getModelConverterClasses(), "copied config must retain converter class names");
            assertInstanceOf(
                    HashSet.class,
                    copied.getModelConverterClasses(),
                    "Swagger 2.2.44 deepCopy loses LinkedHashSet declaration order by deserializing into HashSet");
            assertEquals(
                    declared,
                    copied.getModelConverterClasses(),
                    "deepCopy must preserve the converter class name set membership");
        }

        /**
         * Regression for {@code vertiquehq/vertique-dev#395}: {@code Future<ReadStream<SseEvent>>}
         * must resolve to the SSE string schema through the <em>actual</em> plugin configuration
         * path — {@link ContextUtils#deepCopy} then {@link GenericOpenApiContext}'s {@code
         * buildModelConverters}, then sequential {@link ModelConverters#addConverter} prepends —
         * for either pom declaration order.
         *
         * @param sseBeforeFuture {@code true} when the LinkedHashSet is seeded as {@code [Sse,
         *     Future]}, {@code false} for {@code [Future, Sse]}
         */
        @ParameterizedTest(name = "declared order sseBeforeFuture={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName(
                "deepCopy + buildModelConverters: Future<ReadStream<SseEvent>> is an SSE string in either pom order")
        void futureReadStreamSse_resolvesAsStringThroughSwaggerConfigPath(boolean sseBeforeFuture) throws Exception {
            LinkedHashSet<String> declared = sseBeforeFuture
                    ? new LinkedHashSet<>(
                            List.of(SseModelConverter.class.getName(), FutureModelConverter.class.getName()))
                    : new LinkedHashSet<>(
                            List.of(FutureModelConverter.class.getName(), SseModelConverter.class.getName()));

            List<ModelConverter> registry = registryAfterSwaggerConfigPath(declared);
            ModelConverterContextImpl context = new ModelConverterContextImpl(registry);

            JavaType readStreamOfSse = Json.mapper()
                    .getTypeFactory()
                    .constructParametricType(ReadStream.class, dev.vertique.rest.core.sse.SseEvent.class);
            JavaType futureOfStream =
                    Json.mapper().getTypeFactory().constructParametricType(Future.class, readStreamOfSse);

            Schema<?> resolved = context.resolve(new AnnotatedType().type(futureOfStream));

            assertInstanceOf(StringSchema.class, resolved, "expected SSE string schema but was: " + resolved);
            assertEquals("string", resolved.getType());
            assertEquals("SSE event stream", resolved.getDescription());
            assertTrue(
                    registry.stream().anyMatch(SseModelConverter.class::isInstance),
                    "registry must include SseModelConverter");
            assertTrue(
                    registry.stream().anyMatch(FutureModelConverter.class::isInstance),
                    "registry must include FutureModelConverter");
        }

        /**
         * {@link FutureModelConverter} restarts resolution after unwrap, so {@code
         * Future<BigDecimal>} reaches {@link BigDecimalModelConverter} even when that converter sits
         * ahead of Future in {@link #chainConverters()} (the order sequential {@code addConverter}
         * prepends produce from the hello-example declaration).
         */
        @Test
        @DisplayName("Future<BigDecimal> resolves as the decimal-string schema (Future restarts resolution)")
        void futureOfBigDecimal_resolvesAsDecimalStringBecauseFutureRestartsResolution() {
            ModelConverterContextImpl context = new ModelConverterContextImpl(chainConverters());
            JavaType futureOfBigDecimal =
                    Json.mapper().getTypeFactory().constructParametricType(Future.class, BigDecimal.class);

            Schema<?> resolved = context.resolve(new AnnotatedType().type(futureOfBigDecimal));

            assertNotNull(resolved, "Future<BigDecimal> must resolve to a schema through the registered chain");
            assertDecimalStringSchema(resolved, "Future<BigDecimal>");
            assertNull(resolved.getProperties(), "the Future must be unwrapped, not resolved as a bean: " + resolved);
        }

        /**
         * Mirrors swagger-integration 2.2.44 {@code GenericOpenApiContext.init}: deep-copy the
         * configuration, {@code buildModelConverters}, then prepend each converter onto a registry
         * that already has a {@link ModelResolver} seed — without touching
         * {@link ModelConverters#getInstance()}.
         *
         * @param declaredClassNames pom-style converter class names in declaration order
         * @return the resulting converter list, head first
         */
        private static List<ModelConverter> registryAfterSwaggerConfigPath(Set<String> declaredClassNames)
                throws Exception {
            SwaggerConfiguration config =
                    new SwaggerConfiguration().modelConverterClasses(new LinkedHashSet<>(declaredClassNames));
            OpenAPIConfiguration copied = ContextUtils.deepCopy(config);
            Set<ModelConverter> built = new ConverterBuildContext().exposeBuildModelConverters(copied);
            assertNotNull(built, "buildModelConverters must construct the configured converters");

            List<ModelConverter> registry = new ArrayList<>();
            registry.add(new ModelResolver(Json.mapper()));
            for (ModelConverter converter : built) {
                registry.add(0, converter);
            }
            return registry;
        }

        /**
         * Test-only subclass that exposes {@link GenericOpenApiContext#buildModelConverters} so the
         * regression can call the same protected factory the plugin uses after {@code deepCopy}.
         */
        private static final class ConverterBuildContext extends GenericOpenApiContext<ConverterBuildContext> {
            Set<ModelConverter> exposeBuildModelConverters(OpenAPIConfiguration configuration) throws Exception {
                return buildModelConverters(configuration);
            }
        }
    }

    // --- Helpers ---

    /**
     * Resolves {@code type} through a fresh explicit converter chain and returns the resulting
     * object schema, following the {@code $ref} into the context's defined models when the chain
     * returned a reference rather than the model itself.
     *
     * @param type the fixture type to resolve
     * @return the resolved object schema
     */
    private static Schema<?> resolveModel(Class<?> type) {
        ModelConverterContextImpl context = new ModelConverterContextImpl(chainConverters());
        Schema<?> resolved = context.resolve(new AnnotatedType().type(type));
        assertNotNull(resolved, type.getSimpleName() + " must resolve to a schema through the converter chain");

        String ref = resolved.get$ref();
        if (ref == null) {
            return resolved;
        }
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        Schema<?> model = context.getDefinedModels().get(name);
        assertNotNull(model, "defined models must contain '" + name + "', but were: " + context.getDefinedModels());
        return model;
    }

    /**
     * Looks up a named property on a schema, failing the test with a clear message if it is absent.
     *
     * @param owner the owning schema
     * @param propertyName the property name
     * @return the property's schema
     */
    private static Schema<?> property(Schema<?> owner, String propertyName) {
        Map<String, Schema> properties = owner.getProperties();
        assertNotNull(properties, "schema must carry properties: " + owner);
        Schema<?> propertySchema = properties.get(propertyName);
        assertNotNull(propertySchema, "property '" + propertyName + "' must be present on schema: " + owner);
        return propertySchema;
    }

    /**
     * Asserts that {@code schema} is the decimal-string schema
     * {@link BigDecimalModelConverter} emits: a {@code string} type, {@code decimal} format, the
     * anchored plain decimal pattern, and the 100-character max length.
     *
     * @param schema the schema to assert against
     * @param label a human-readable property name used in assertion failure messages
     */
    private static void assertDecimalStringSchema(Schema<?> schema, String label) {
        assertEquals("string", schema.getType(), label + " must resolve to a string schema");
        assertEquals("decimal", schema.getFormat(), label + " must carry the decimal format");
        assertEquals(DECIMAL_PATTERN, schema.getPattern(), label + " must carry the anchored plain decimal pattern");
        assertEquals(Integer.valueOf(100), schema.getMaxLength(), label + " must carry the 100-character max length");
    }

    /**
     * Asserts that {@code schema} carries neither of the {@code present}/{@code empty} properties a
     * JavaBean-resolved JDK optional would expose.
     *
     * @param schema the schema to assert against
     * @param label a human-readable property name used in assertion failure messages
     */
    private static void assertNoOptionalWrapperProperties(Schema<?> schema, String label) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        assertFalse(properties.containsKey("present"), label + " schema must not carry a present property");
        assertFalse(properties.containsKey("empty"), label + " schema must not carry an empty property");
    }

    /**
     * Checks whether {@code propertyName} appears in {@code owner}'s required list. An absent
     * required list counts as not required.
     *
     * @param owner the owning schema
     * @param propertyName the property name to look for
     * @return {@code true} if {@code propertyName} is listed as required
     */
    private static boolean isRequired(Schema<?> owner, String propertyName) {
        List<String> required = owner.getRequired();
        return required != null && required.contains(propertyName);
    }
}
