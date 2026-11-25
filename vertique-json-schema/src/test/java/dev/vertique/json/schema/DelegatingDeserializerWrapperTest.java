// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mapper-wide {@link BeanDeserializerModifier} that wraps <em>every</em> bean deserializer in a
 * {@link DelegatingDeserializer} subclass — forwarding every operation to the original bean
 * deserializer through {@link DelegatingDeserializer#getDelegatee()} — is a legitimate, if unusual,
 * module shape: {@code deserialize} still ends up calling the wrapped bean deserializer, so the type
 * binds exactly as it would unwrapped.
 *
 * <p>{@code InputPropertyDescriber#describe} unwraps such a pure forwarder through {@code
 * getDelegatee()} before deciding bean-ness, mirroring its {@code TypeWrappedDeserializer} unwrap, so
 * the wrapped bean is described like any other bean rather than refused like a genuine type-level
 * deserializer override. The unwrap applies at every seam that inspects a deserializer: the root, an
 * {@code @JsonUnwrapped} child, and a member-level case-insensitive child. A wrapper that changes the
 * wire shape is still refused.
 */
class DelegatingDeserializerWrapperTest {

    /** Forwards every operation to the delegate, exactly as a bean-preserving wrapper module would. */
    static final class ForwardingDelegatingDeserializer extends DelegatingDeserializer {
        ForwardingDelegatingDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new ForwardingDelegatingDeserializer(newDelegatee);
        }
    }

    /** An ordinary constrained DTO, wrapped at the mapper level rather than annotated itself. */
    static final class ConstrainedDto {
        @Size(max = 3)
        public String name;
    }

    private static JsonMapperProfile profile(ObjectMapper mapper) {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of();
            }
        };
    }

    private static ObjectMapper mapperWithForwardingWrapperForEveryBean() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyDeserializer(
                            DeserializationConfig config,
                            com.fasterxml.jackson.databind.BeanDescription beanDesc,
                            JsonDeserializer<?> deserializer) {
                        return new ForwardingDelegatingDeserializer(deserializer);
                    }
                });
            }
        });
        return mapper;
    }

    private static JsonNode document(Type type, ObjectMapper mapper) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile(mapper)).generateCanonical(type));
    }

    @Test
    @DisplayName("a mapper-wide DelegatingDeserializer wrapper over every bean deserializer must still"
            + " generate a document for an ordinary constrained DTO, described through the delegate")
    void mapperWideDelegatingWrapperStillGenerates() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(ConstrainedDto.class, mapper);

        assertFalse(
                document.path("properties").isMissingNode(),
                "the wrapped bean must still be described as an object with properties, not"
                        + " refused as an opaque custom-deserializer type; document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("name").toString(),
                "the wrapped bean's own @Size(max = 3) must still be published, unwrapping"
                        + " through DelegatingDeserializer#getDelegatee() to reach the real bean deserializer;"
                        + " document: " + document);
    }

    // --- The unwrap reaches an @JsonUnwrapped child's deserializer too ---

    /** An ordinary constrained member, unwrapped onto the parent rather than published as its own object. */
    static final class UnwrappedPlainChild {
        @Size(max = 3)
        public String name;
    }

    /**
     * Carries {@link UnwrappedPlainChild} through {@code @JsonUnwrapped}, wrapped at the mapper level like
     * {@link ConstrainedDto}.
     */
    static final class UnwrappedPlainParent {
        @JsonUnwrapped
        public UnwrappedPlainChild child;
    }

    /**
     * {@code populateObjectSchema}'s unwrapped-child loop obtains the child's root deserializer through
     * {@code rootDeserializer(property.getType())} and calls {@code unwrappingDeserializer(...)} on it,
     * so it must route through {@link InputPropertyDescriber}'s {@code unwrapDelegating} helper the way
     * {@code describe}'s own root seam does. Under the same mapper-wide {@code
     * BeanDeserializerModifier} {@link #mapperWithForwardingWrapperForEveryBean()} installs, the child's
     * root deserializer is itself a {@code ForwardingDelegatingDeserializer}, whose {@code
     * unwrappingDeserializer(transformer)} never yields a {@code BeanDeserializerBase}; without the
     * unwrap the loop's {@code instanceof BeanDeserializerBase} check would fail and the unwrapped
     * child would be silently skipped.
     *
     * <p>This assertion is a positive guard rather than the loop's own discriminating proof. {@code
     * UnwrappedPlainChild}'s {@code name} member is an <em>ordinary, plain</em> property — not hidden,
     * not an alias, not on an any-setter type — and the schema library's own generation independently
     * publishes a bare {@code @JsonUnwrapped} member's plain properties onto the parent, entirely apart
     * from this describer's unwrapped-child loop. The loop is discriminating only for a shape the
     * library's own flattening does not cover: a hidden member or an alias spelling that must be
     * reserved or published through the loop's own alias/reservation fold, which this class's
     * hidden-member test below and {@code ProfiledSchemaSynthesisIT} exercise instead.
     */
    @Test
    @DisplayName("under a mapper-wide DelegatingDeserializer wrapper, an @JsonUnwrapped child's own"
            + " constraint must still be published, not silently skipped by the unwrapped-child loop")
    void unwrappedChildUnderMapperWideDelegatingWrapperIsDescribed() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(UnwrappedPlainParent.class, mapper);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("name").toString(),
                "the unwrapped child's own @Size(max = 3) must be published even though the child's root"
                        + " deserializer is itself wrapped by the mapper-wide DelegatingDeserializer forwarder:"
                        + " the unwrapped-child loop must unwrap it through the unwrapDelegating(...) helper,"
                        + " or the instanceof BeanDeserializerBase check fails and the child is silently"
                        + " skipped; document: " + document);
    }

    // --- The root-seam unwrap refuses a subclass that changes the wire shape ---

    /** A bean-like DTO whose deserializer a profile module replaces with a shape-changing wrapper. */
    static final class ShapeChangedDto {
        @Size(max = 3)
        public String name;
    }

    /**
     * A {@link DelegatingDeserializer} subclass that is <em>not</em> a pure forwarder: it accepts a
     * compact string form ({@code "abc"} instead of {@code {"name":"abc"}}) before ever reaching the
     * delegate, so the type it wraps binds a wire shape the delegate's own bean description does not
     * capture. Unwrapping every {@code DelegatingDeserializer} subclass unconditionally would have no
     * way to tell this shape-changing override apart from {@link ForwardingDelegatingDeserializer}'s
     * pure forwarding, and would describe the type from the delegate's {@code BeanDeserializerBase} as
     * if the override did not exist.
     */
    static final class StringFormAcceptingDelegatingDeserializer extends DelegatingDeserializer {
        StringFormAcceptingDelegatingDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new StringFormAcceptingDelegatingDeserializer(newDelegatee);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                ShapeChangedDto dto = new ShapeChangedDto();
                dto.name = parser.getValueAsString();
                return dto;
            }
            return super.deserialize(parser, ctxt);
        }
    }

    private static JsonMapperProfile profileWithShapeChangingWrapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyDeserializer(
                            DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                        if (beanDesc.getBeanClass() == ShapeChangedDto.class) {
                            return new StringFormAcceptingDelegatingDeserializer(deserializer);
                        }
                        return deserializer;
                    }
                });
            }
        });
        return profile(mapper);
    }

    /**
     * {@code unwrapDelegating} must not accept just any {@code DelegatingDeserializer} subclass: one
     * that overrides {@code deserialize} to accept a wire form the delegate's own bean description does
     * not describe is refused, rather than described from the delegate's plain bean shape with the
     * string-form override this profile actually binds silently dropped.
     */
    @Test
    @DisplayName("a DelegatingDeserializer subclass that changes the wire shape must be refused, not"
            + " unwrapped straight to the delegate's bean description")
    void delegatingDeserializerThatChangesTheWireShapeIsRefused() {
        JsonMapperProfile profile = profileWithShapeChangingWrapper();

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(ShapeChangedDto.class),
                "a DelegatingDeserializer subclass that overrides deserialize(...) to accept a different"
                        + " wire form must be refused with the custom-deserializer diagnostic, not unwrapped"
                        + " straight to the delegate's plain bean description");
        assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                "unexpected message: " + failure.getMessage());
    }

    // --- The inline case-insensitive member path unwraps a delegating wrapper too ---

    /** The case-insensitively bound member's own type, carrying a constraint like {@link ConstrainedDto}. */
    static final class MemberCaseInsensitiveChild {
        @Size(max = 3)
        public String name;
    }

    /** Binds {@link MemberCaseInsensitiveChild} case-insensitively only through this member's {@code @JsonFormat}. */
    static final class MemberCaseInsensitiveHolder {
        public String label;

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public MemberCaseInsensitiveChild child;
    }

    /**
     * {@code propertySchema}'s inline case-insensitive path computes {@code
     * unwrap(property.getValueDeserializer())} before checking {@code instanceof BeanDeserializerBase
     * nestedBean && nestedBean.isCaseInsensitive()}, so that {@code unwrap} must see through a {@code
     * DelegatingDeserializer} as well as a {@code TypeWrappedDeserializer}. Under the same mapper-wide
     * wrapper as {@link #mapperWithForwardingWrapperForEveryBean()}, the member's own contextual
     * deserializer is still a {@code ForwardingDelegatingDeserializer}; the member must nevertheless be
     * described inline with {@code patternProperties}, not by {@code $ref} to the type's plain,
     * case-sensitive shared definition. A {@code $ref} description would be <em>stricter</em> than the
     * real Jackson binder, which binds the member case-insensitively regardless of how the description
     * is built — over-restriction of the generated schema against traffic the binder accepts.
     */
    @Test
    @DisplayName("under a mapper-wide DelegatingDeserializer wrapper, a member-level case-insensitive"
            + " child must still be described inline with patternProperties, not by $ref to the plain"
            + " (case-sensitive) shared definition")
    void memberLevelCaseInsensitiveChildUnderMapperWideWrapperIsDescribedInline() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(MemberCaseInsensitiveHolder.class, mapper);
        JsonNode child = document.path("properties").path("child");

        assertFalse(
                child.has("$ref"),
                "a member bound case-insensitively only through its own @JsonFormat must not fall back to"
                        + " the type's ordinary, case-sensitive $ref under the wrapper module; document: "
                        + document);
        assertEquals("object", child.path("type").asText(null), "document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "document: " + document);
        JsonNode childPatternProperties = child.path("patternProperties");
        assertTrue(
                childPatternProperties.isObject() && !childPatternProperties.isEmpty(),
                "the inline nested description under the wrapper module must carry its own"
                        + " patternProperties, exactly as it does without the wrapper; document: " + document);
    }

    // --- A hidden member on an unwrapped child of an any-setter parent ---

    /**
     * A {@code @Schema(hidden = true)}-constrained unwrapped member — the same shape {@code
     * UnwrappedAnySetterFoldingTest.HiddenChild} probes without the wrapper.
     */
    static final class UnwrappedHiddenChild {
        @Schema(hidden = true)
        @Size(max = 3)
        public String token;
    }

    /** Carries {@link UnwrappedHiddenChild} through {@code @JsonUnwrapped} on an any-setter type. */
    static final class UnwrappedHiddenChildAnySetterParent {
        @JsonUnwrapped
        public UnwrappedHiddenChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * Unlike the plain-property case above, a hidden member is never published by the type's own
     * class-level members — it can only reach the document through {@code
     * foldUnwrappedChildIntoParentPlan}, which runs only for a sibling the unwrapped-child loop
     * processed as a {@code BeanDeserializerBase}. Under the mapper-wide wrapper the loop must unwrap
     * {@link UnwrappedHiddenChild} so the fold runs and {@code "token"} is reserved; otherwise it would
     * be neither published nor reserved and would fall through to the extras bucket unconstrained.
     */
    @Test
    @DisplayName("under a mapper-wide DelegatingDeserializer wrapper, an unwrapped child's hidden member"
            + " on an any-setter parent must still be reserved, not left to fall through to the extras bucket")
    void hiddenUnwrappedChildMemberIsReservedUnderMapperWideDelegatingWrapper() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(UnwrappedHiddenChildAnySetterParent.class, mapper);

        assertFalse(
                document.path("properties").has("token"),
                "a @Schema(hidden = true) member must stay unpublished, as before; document: " + document);
        JsonNode reservedNames = document.path("propertyNames").path("not").path("enum");
        boolean reserved = false;
        if (reservedNames.isArray()) {
            for (JsonNode entry : reservedNames) {
                if ("token".equals(entry.asText())) {
                    reserved = true;
                }
            }
        }
        assertTrue(
                reserved,
                "the unwrapped child's hidden member \"token\" must be"
                        + " reserved under the mapper-wide wrapper profile too, exactly as it is without the"
                        + " wrapper (UnwrappedAnySetterFoldingTest.unwrappedChildHiddenMemberIsReserved),"
                        + " refusing a key spelling it outright rather than letting it fall through to"
                        + " additionalProperties as an unconstrained extra; document: " + document);
    }

    // --- A case-insensitive unwrapped child is refused, not silently skipped ---

    /** Bound case-insensitively at the class level, so any position unwrapping it inherits that binding. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveUnwrappedChild {
        public String name;
    }

    /** Carries {@link CaseInsensitiveUnwrappedChild} through a bare {@code @JsonUnwrapped}. */
    static final class CaseInsensitiveUnwrappedParent {
        @JsonUnwrapped
        public CaseInsensitiveUnwrappedChild child;
    }

    /**
     * Control (no wrapper): {@code requireCaseSensitive} — called immediately after the loop's {@code
     * instanceof BeanDeserializerBase} check succeeds — refuses a case-insensitively bound unwrapped
     * child with a bounded diagnostic. This is the behavior the wrapper case below must match.
     */
    @Test
    @DisplayName("control: without the wrapper, a case-insensitively bound unwrapped child is refused")
    void caseInsensitiveUnwrappedChildIsRefusedWithoutTheWrapper() {
        JsonMapperProfile profile = profile(new ObjectMapper());

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile)
                        .generateCanonical(CaseInsensitiveUnwrappedParent.class),
                "a case-insensitively bound unwrapped child must be refused, not silently"
                        + " described, when the loop actually reaches requireCaseSensitive");
        assertTrue(
                failure.getMessage().contains("bound case-insensitively"),
                "unexpected message: " + failure.getMessage());
    }

    /**
     * Under the mapper-wide wrapper, {@link CaseInsensitiveUnwrappedChild}'s root deserializer is
     * itself a {@code ForwardingDelegatingDeserializer}. The loop must unwrap it so the {@code
     * instanceof BeanDeserializerBase} check succeeds and the {@code requireCaseSensitive} refusal
     * runs; otherwise the loop would {@code continue} <em>before</em> that call and generation would
     * succeed silently, describing the parent with no {@code "name"} property and no diagnostic at all
     * — a soundness regression, since the type is genuinely case-insensitively bound.
     */
    @Test
    @DisplayName("under a mapper-wide DelegatingDeserializer wrapper, a case-insensitively bound unwrapped"
            + " child must be refused, exactly as it is without the wrapper, not silently skipped")
    void caseInsensitiveUnwrappedChildIsRefusedUnderMapperWideDelegatingWrapper() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();
        JsonMapperProfile profile = profile(mapper);

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile)
                        .generateCanonical(CaseInsensitiveUnwrappedParent.class),
                "generation must refuse this shape exactly as it does without the wrapper (see the"
                        + " control above): the loop must unwrap the wrapper so that requireCaseSensitive is"
                        + " reached, rather than skipping the child and succeeding silently with the"
                        + " case-insensitively bound child's own property missing and no diagnostic at all");
        assertTrue(
                failure.getMessage().contains("bound case-insensitively"),
                "unexpected message: " + failure.getMessage());
    }
}
