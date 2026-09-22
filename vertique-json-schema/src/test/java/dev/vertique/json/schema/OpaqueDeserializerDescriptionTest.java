// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import java.io.IOException;
import java.lang.reflect.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * W4 (review finding): the custom-deserializer refusal in {@link InputPropertyDescriber#describe}
 * must fire only for a <em>bean-like</em> type whose own deserializer was replaced by an explicit
 * type-level {@code @JsonDeserialize(using = ...)} — never for a foreign, opaque type a module
 * registers a plain {@link com.fasterxml.jackson.databind.JsonDeserializer} for (a scalar, container,
 * node, or Vert.x-style wrapper such as {@code JsonObject}/{@code JsonArray}/{@code Buffer}). Before
 * the fix, {@link InputPropertyDescriber#describe} refused <em>any</em> type whose resolved
 * deserializer was not a {@code BeanDeserializerBase}, which included every module-registered
 * deserializer regardless of whether the type looked like a bean at all — confirmed by disassembly
 * that Vert.x's own {@code JsonObjectDeserializer}/{@code JsonArrayDeserializer}/{@code
 * BufferDeserializer} extend the plain {@code JsonDeserializer<T>} base, not a bean deserializer.
 *
 * <p>This module does not depend on {@code vertx-core} (its own architecture rule freezes that), so
 * the opaque-wrapper shape is reproduced here with a hand-rolled type carrying a module-registered
 * {@link StdDeserializer}, no bean properties, and no class-level {@code @JsonDeserialize} annotation
 * — exactly the shape a module-registered Vert.x deserializer has from this generator's point of
 * view. A DTO carrying an explicit type-level override stays refused, proving the narrowing did not
 * also swallow the genuine bean-like case.
 */
class OpaqueDeserializerDescriptionTest {

    /** An opaque wrapper type: no bean properties, deserialized by a module-registered plain deserializer. */
    static final class OpaqueWrapper {
        private final String raw;

        OpaqueWrapper(String raw) {
            this.raw = raw;
        }

        String raw() {
            return raw;
        }
    }

    static final class OpaqueWrapperDeserializer extends StdDeserializer<OpaqueWrapper> {
        OpaqueWrapperDeserializer() {
            super(OpaqueWrapper.class);
        }

        @Override
        public OpaqueWrapper deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws IOException {
            return new OpaqueWrapper(parser.getValueAsString());
        }
    }

    /** A DTO holding the opaque wrapper as a member, so both the root and nested positions are proven. */
    static final class HoldsOpaqueWrapper {
        public OpaqueWrapper wrapper;
    }

    /** A genuinely bean-like type whose class carries an explicit type-level override: still refused. */
    @JsonDeserialize(using = OverriddenBean.Deserializer.class)
    static final class OverriddenBean {
        public String name;
        public int amount;

        static final class Deserializer extends StdDeserializer<OverriddenBean> {
            Deserializer() {
                super(OverriddenBean.class);
            }

            @Override
            public OverriddenBean deserialize(
                    com.fasterxml.jackson.core.JsonParser parser,
                    com.fasterxml.jackson.databind.DeserializationContext ctxt) {
                throw new UnsupportedOperationException("not exercised");
            }
        }
    }

    private static JsonMapperProfile profileWithOpaqueWrapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("opaque-wrapper-test");
        module.addDeserializer(OpaqueWrapper.class, new OpaqueWrapperDeserializer());
        mapper.registerModule(module);
        return JsonMapperProfiles.of(JsonProfileId.of("opaque-wrapper-test"), mapper);
    }

    /**
     * F1 (security review round 1, HIGH): a bean-like application DTO whose deserializer is attached
     * through a profile module — {@code SimpleModule.addDeserializer(Money.class, ...)} — rather than
     * a class-level {@code @JsonDeserialize(using = ...)}. {@code declaresOwnDeserializerOverride}
     * alone missed this: the class carries no annotation, so before the fix this fell into the same
     * "opaque wrapper" branch as {@link OpaqueWrapper} and was described as {@code {}}, dropping the
     * {@code @Max(100)} constraint the field walk on {@code main} always published.
     */
    static final class Money {
        @jakarta.validation.constraints.Max(100)
        public int amount;
    }

    static final class MoneyDeserializer extends StdDeserializer<Money> {
        MoneyDeserializer() {
            super(Money.class);
        }

        @Override
        public Money deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext ctxt) {
            throw new UnsupportedOperationException("not exercised");
        }
    }

    /** A DTO holding the module-deserialized bean-like type as a member. */
    static final class HoldsMoney {
        public Money money;
    }

    private static JsonMapperProfile profileWithModuleRegisteredBeanDeserializer() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("module-registered-bean-deserializer-test");
        module.addDeserializer(Money.class, new MoneyDeserializer());
        mapper.registerModule(module);
        return JsonMapperProfiles.of(JsonProfileId.of("module-registered-bean-deserializer-test"), mapper);
    }

    @Test
    @DisplayName("F1: a bean-like type with a module-registered (non-annotated) deserializer is refused, not"
            + " described as {} — every constraint on it must not be silently dropped")
    void beanLikeTypeWithModuleRegisteredDeserializerIsRefused() {
        JsonMapperProfile profile = profileWithModuleRegisteredBeanDeserializer();
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(HoldsMoney.class));
        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                () -> "unexpected message: " + failure.getMessage());
    }

    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(profileWithOpaqueWrapper())
                .generateCanonical(type));
    }

    @Test
    @DisplayName("W4: a module-registered, non-bean deserializer for a foreign opaque type is not refused")
    void opaqueTypeAtRootIsDescribedNotRefused() {
        JsonNode document = inputDocument(OpaqueWrapper.class);
        // Unconstrained: the generator cannot know the wire shape of an opaque wrapper it has no bean
        // properties for, so it describes it exactly like Object.class/JsonNode.class — accepts any
        // JSON value — never refuses generation outright. The root document additionally carries
        // $schema, which every generated root document does regardless of shape.
        assertEquals("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}", document.toString());
    }

    @Test
    @DisplayName("W4: an opaque wrapper nested as a member is described, not refused")
    void opaqueTypeAsMemberIsDescribedNotRefused() {
        JsonNode document = inputDocument(HoldsOpaqueWrapper.class);
        JsonNode wrapperSchema = document.path("properties").path("wrapper");
        assertEquals("{}", wrapperSchema.toString());
    }

    @Test
    @DisplayName(
            "W4 regression guard: a bean-like type with its own class-level @JsonDeserialize override stays refused")
    void beanLikeTypeWithOwnOverrideStaysRefused() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(OverriddenBean.class));
        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                () -> "unexpected message: " + failure.getMessage());
    }
}
