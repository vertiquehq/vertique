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
import io.swagger.v3.oas.models.media.Schema;
import io.vertx.core.Future;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Composition tests for this module's {@link ModelConverter} implementations: they pin the schema
 * shape {@link BigDecimalModelConverter} and {@link ScalarOptionalModelConverter} produce
 * <em>together</em> for record DTOs, which no single-converter unit test can prove.
 *
 * <p>{@link FutureModelConverter} takes no part in those DTO assertions: none of the fixtures below
 * has a {@code Future}-typed component, so nothing in them would notice its absence. It is
 * exercised by {@link RegistrationOrder} instead, which resolves a {@code Future<BigDecimal>}
 * through the same chain.
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
 * {@link FutureModelConverter} carries no DTO assertion at all; its removal reddens only
 * {@link RegistrationOrder#futureOfBigDecimal_resolvesAsBareNumberBecauseBigDecimalConverterIsBehindTheUnwrap()},
 * where the un-unwrapped {@code Future} reaches the {@link ModelResolver} and resolves as a
 * JavaBean object schema with a {@code complete} property instead of a number.
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
     * Builds the converter chain in the order the {@code swagger-maven-plugin} registration
     * actually produces, then a {@link ModelResolver} tail so record types resolve to object
     * schemas at all (without a resolver at the tail the chain exhausts and returns {@code null}).
     *
     * <p>The rule: {@link ModelConverters#addConverter} inserts at index {@code 0}, so each
     * registration lands ahead of the previous one and a {@code <modelConverterClasses>} list
     * composes <strong>back-to-front</strong> relative to how it reads — see
     * {@link RegistrationOrder#addConverter_prependsSoDeclaredOrderIsReversed()}, which pins that
     * behaviour empirically, and {@code vertiquehq/vertique-dev#395}.
     *
     * <p>The head-first order below is what a declaration of {@code FutureModelConverter},
     * {@code BigDecimalModelConverter}, {@code ScalarOptionalModelConverter} composes to.
     * {@code examples/vertique-example-hello/pom.xml} is an illustration of such a declaration
     * rather than the authority for this chain: nothing enforces what that pom declares, so read
     * the rule above, not the example's current contents.
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

    // --- Registration order ---

    @Nested
    @DisplayName("swagger-maven-plugin registration order")
    class RegistrationOrder {

        /**
         * Pins what {@link ModelConverters#addConverter} does to the declared registration order.
         * This is the one assertion covering the registration <em>mechanism</em> that every other
         * test in this class deliberately bypasses by building the chain explicitly.
         *
         * <p>Registering the three converters in the order
         * {@code examples/vertique-example-hello/pom.xml} declares them yields the reverse order in
         * {@link ModelConverters#getConverters()}: {@code addConverter} inserts at index {@code 0}.
         * A plugin configuration therefore composes back-to-front relative to how it reads, which
         * is the concern raised in {@code vertiquehq/vertique-dev#395}. This test pins the observed
         * behaviour; it does not endorse or change it.
         *
         * <p>A throwaway {@code new ModelConverters()} is used rather than
         * {@link ModelConverters#getInstance()} so this test mutates no converter registry outside
         * itself — in particular it never touches the process-wide singleton that every other test
         * and any Swagger tooling in the same JVM share. That is the isolation that matters here;
         * it is not total isolation from global state, because the {@link ModelResolver} a fresh
         * {@code ModelConverters} seeds registers Jackson modules on the static
         * {@link Json#mapper()} — exactly as {@link #chainConverters()} does on every call.
         * Jackson dedupes by module type id, so that registration is idempotent and harmless.
         */
        @Test
        @DisplayName("addConverter prepends, so the pom's declared order is reversed in the resolved chain")
        void addConverter_prependsSoDeclaredOrderIsReversed() {
            ModelConverters registry = new ModelConverters();
            FutureModelConverter future = new FutureModelConverter();
            BigDecimalModelConverter bigDecimal = new BigDecimalModelConverter();
            ScalarOptionalModelConverter scalarOptional = new ScalarOptionalModelConverter();

            // The pom's declared order.
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
         * Pins the one type for which the prepend inversion is <em>not</em> benign:
         * {@code Future<BigDecimal>}.
         *
         * <p>{@link FutureModelConverter#resolve} unwraps {@code Future<T>} and hands the inner
         * type to {@link ConverterChain#delegate} — it continues down the <em>remaining</em> chain
         * rather than restarting at the head via
         * {@link io.swagger.v3.core.converter.ModelConverterContext#resolve}. In the registered
         * order {@code [ScalarOptional, BigDecimal, Future, ModelResolver]}, a
         * {@code Future<BigDecimal>} therefore passes {@link ScalarOptionalModelConverter} (raw
         * class is {@code Future} — no match) and {@link BigDecimalModelConverter} (same — no
         * match) before {@link FutureModelConverter} unwraps it, at which point only the bare
         * {@link ModelResolver} is left. The converter that would have produced the decimal-string
         * schema now sits <em>behind</em> the unwrap, so the type resolves as a plain JSON
         * {@code number}.
         *
         * <p>This test records the observed behaviour; it does <strong>not</strong> assert desired
         * behaviour. The {@code number} outcome is a consequence of the registration inversion
         * raised in {@code vertiquehq/vertique-dev#395}, not a shape this module intends to emit —
         * the declared pom order resolves the same type to the decimal-string schema, which
         * {@code BigDecimalModelConverterTest#bigDecimalInsideFuture_resolvedWhenChainedAfterFutureConverter}
         * pins. If #395 is resolved, this test is expected to change with it.
         *
         * <p>The consequence is <strong>latent</strong>: no production code currently returns a
         * {@code Future<BigDecimal>} — verified across {@code examples/} and
         * {@code vertique-rest/} — so no generated spec is wrong today. This test is where the
         * surprise is written down should such a return type appear.
         */
        @Test
        @DisplayName("Future<BigDecimal> resolves as a bare number: the BigDecimal converter lands behind the"
                + " unwrap (observed consequence of #395)")
        void futureOfBigDecimal_resolvesAsBareNumberBecauseBigDecimalConverterIsBehindTheUnwrap() {
            ModelConverterContextImpl context = new ModelConverterContextImpl(chainConverters());
            JavaType futureOfBigDecimal =
                    Json.mapper().getTypeFactory().constructParametricType(Future.class, BigDecimal.class);

            Schema<?> resolved = context.resolve(new AnnotatedType().type(futureOfBigDecimal));

            assertNotNull(resolved, "Future<BigDecimal> must resolve to a schema through the registered chain");
            assertEquals(
                    "number",
                    resolved.getType(),
                    "in the registered order the unwrapped BigDecimal reaches only the ModelResolver, so it resolves"
                            + " as a bare number, but was: " + resolved);
            // Negative half of the claim: this is neither BigDecimalModelConverter's decimal-string
            // schema nor the JavaBean shape a chain without FutureModelConverter would produce.
            assertNull(resolved.getFormat(), "the bare number schema must carry no decimal format: " + resolved);
            assertNull(resolved.getPattern(), "the bare number schema must carry no decimal pattern: " + resolved);
            assertNull(resolved.getProperties(), "the Future must be unwrapped, not resolved as a bean: " + resolved);
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
