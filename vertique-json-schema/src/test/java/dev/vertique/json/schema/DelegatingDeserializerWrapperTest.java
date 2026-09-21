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
 * W2 (spike/deserializer-driven-schema round 4 ruling): a mapper-wide {@link BeanDeserializerModifier}
 * that wraps <em>every</em> bean deserializer in a {@link DelegatingDeserializer} subclass — forwarding
 * every operation to the original bean deserializer through {@link DelegatingDeserializer#getDelegatee()}
 * — is a legitimate, if unusual, module shape: {@code deserialize} still ends up calling the wrapped
 * bean deserializer, so the type binds exactly as it would unwrapped.
 *
 * <p>{@code InputPropertyDescriber#describe} only recognizes a root deserializer that is directly an
 * instance of {@code BeanDeserializerBase} (after unwrapping a {@code TypeWrappedDeserializer}); a
 * {@code DelegatingDeserializer} wrapper is not unwrapped the same way, so the wrapped bean is
 * misclassified exactly like a genuine type-level deserializer override — refused (F1's own posture),
 * even though the wrapped type is bean-like and its own {@code BeanDeserializerBase} is reachable one
 * hop away through {@code getDelegatee()}.
 *
 * <p>The owner ruling records the fix direction (unwrap through {@code getDelegatee()} before deciding
 * bean-ness, mirroring the existing {@code TypeWrappedDeserializer} unwrap) but this class only authors
 * the proof, never the production change.
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
    @DisplayName("W2: a mapper-wide DelegatingDeserializer wrapper over every bean deserializer must still"
            + " generate a document for an ordinary constrained DTO, described through the delegate")
    void mapperWideDelegatingWrapperStillGenerates() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(ConstrainedDto.class, mapper);

        assertFalse(
                document.path("properties").isMissingNode(),
                "W2 DECISIVE: the wrapped bean must still be described as an object with properties, not"
                        + " refused as an opaque custom-deserializer type; document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("name").toString(),
                "W2 DECISIVE: the wrapped bean's own @Size(max = 3) must still be published, unwrapping"
                        + " through DelegatingDeserializer#getDelegatee() to reach the real bean deserializer;"
                        + " document: " + document);
    }

    // --- C-1 (round 5 review finding, spike/deserializer-driven-schema): the unwrap is root-seam only ---

    /** An ordinary constrained member, unwrapped onto the parent rather than published as its own object. */
    static final class C1Child {
        @Size(max = 3)
        public String name;
    }

    /** Carries {@link C1Child} through {@code @JsonUnwrapped}, wrapped at the mapper level like {@link ConstrainedDto}. */
    static final class C1Parent {
        @JsonUnwrapped
        public C1Child child;
    }

    /**
     * C-1 (independent review, by-reading finding): {@code populateObjectSchema}'s unwrapped-child
     * loop (around {@code InputPropertyDescriber} lines 485-518) obtains the child's root deserializer
     * through {@code rootDeserializer(property.getType())} and calls {@code unwrappingDeserializer(...)}
     * on it directly — it never routes through {@link InputPropertyDescriber}'s {@code unwrapDelegating}
     * helper the way {@code describe}'s own root seam does (W2). Under the same mapper-wide {@code
     * BeanDeserializerModifier} {@link #mapperWithForwardingWrapperForEveryBean()} installs, the child's
     * root deserializer is itself a {@code ForwardingDelegatingDeserializer}, whose {@code
     * unwrappingDeserializer(transformer)} never yields a {@code BeanDeserializerBase} — the loop's
     * {@code instanceof BeanDeserializerBase} check fails and the unwrapped child is silently skipped,
     * publishing neither its property nor its {@code @Size(max = 3)} constraint.
     *
     * <p>REOPENED (round 6 finding): this assertion does not discriminate the loop's own bug and never
     * exercised it. {@code C1Child}'s {@code name} member is an <em>ordinary, plain</em> property — not
     * hidden, not an alias, not on an any-setter type — and the schema library's own generation
     * independently publishes a bare {@code @JsonUnwrapped} member's plain properties onto the parent,
     * entirely apart from this describer's own unwrapped-child loop above. Measured directly: with the
     * loop's {@code renamed} assignment forced to always yield {@code null} (so every unwrapped child is
     * unconditionally skipped by the {@code instanceof BeanDeserializerBase} check, wrapper or no
     * wrapper), this exact shape still generates {@code "name":{"maxLength":3,"type":"string"}} — so the
     * loop's own output was never what this assertion is checking. It is kept as a positive regression
     * guard for that already-independent library behavior, never as the loop's own discriminating proof
     * — the loop's own bug is discriminating only for a shape the library's own flattening does not cover
     * on its own: a hidden member or an alias spelling that must be reserved or published through the
     * loop's own alias/reservation fold, which this class's hidden-member test below and {@code
     * ProfiledSchemaSynthesisIT}'s C-2 gate row exercise instead.
     */
    @Test
    @DisplayName("C-1: under a mapper-wide DelegatingDeserializer wrapper, an @JsonUnwrapped child's own"
            + " constraint must still be published, not silently skipped by the unwrapped-child loop")
    void unwrappedChildUnderMapperWideDelegatingWrapperIsDescribed() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(C1Parent.class, mapper);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("name").toString(),
                "C-1 DECISIVE: the unwrapped child's own @Size(max = 3) must be published even though the"
                        + " child's root deserializer is itself wrapped by the mapper-wide"
                        + " DelegatingDeserializer forwarder — the unwrapped-child loop obtains the child's"
                        + " deserializer through rootDeserializer(...).unwrappingDeserializer(...) directly,"
                        + " never through the unwrapDelegating(...) helper W2's root-seam fix added, so the"
                        + " wrapper is never unwrapped here and the instanceof BeanDeserializerBase check"
                        + " fails, silently skipping the child; document: " + document);
    }

    // --- W-1 (round 5 review finding): the root-seam unwrap does not check the subclass changes nothing ---

    /** A bean-like DTO whose deserializer a profile module replaces with a shape-changing wrapper. */
    static final class W1Dto {
        @Size(max = 3)
        public String name;
    }

    /**
     * A {@link DelegatingDeserializer} subclass that is <em>not</em> a pure forwarder: it accepts a
     * compact string form ({@code "abc"} instead of {@code {"name":"abc"}}) before ever reaching the
     * delegate, so the type it wraps binds a wire shape the delegate's own bean description does not
     * capture. {@code unwrapDelegating} (W2's root-seam fix) accepts any {@code DelegatingDeserializer}
     * subclass unconditionally — it has no way to tell this shape-changing override apart from {@link
     * ForwardingDelegatingDeserializer}'s pure forwarding — so it unwraps straight to the delegate's
     * {@code BeanDeserializerBase} and describes the type from there, as if the override did not exist.
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
                W1Dto dto = new W1Dto();
                dto.name = parser.getValueAsString();
                return dto;
            }
            return super.deserialize(parser, ctxt);
        }
    }

    private static JsonMapperProfile w1ProfileWithShapeChangingWrapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyDeserializer(
                            DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                        if (beanDesc.getBeanClass() == W1Dto.class) {
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
     * W-1 (independent review, by-reading finding): {@code unwrapDelegating} accepts any {@code
     * DelegatingDeserializer} subclass, including one that overrides {@code deserialize} to accept a
     * wire form the delegate's own bean description does not describe. Expected red (pre-fix):
     * generation succeeds and describes the type from the delegate's plain bean shape, silently
     * dropping the string-form override this profile actually binds.
     */
    @Test
    @DisplayName("W-1: a DelegatingDeserializer subclass that changes the wire shape must be refused, not"
            + " unwrapped straight to the delegate's bean description")
    void delegatingDeserializerThatChangesTheWireShapeIsRefused() {
        JsonMapperProfile profile = w1ProfileWithShapeChangingWrapper();

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(W1Dto.class),
                "W-1 DECISIVE: a DelegatingDeserializer subclass that overrides deserialize(...) to accept a"
                        + " different wire form must be refused with the existing custom-deserializer"
                        + " diagnostic (F1), not unwrapped straight to the delegate's plain bean description");
        assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                "unexpected message: " + failure.getMessage());
    }

    // --- S-1 (round 5 review finding): the inline case-insensitive member path unwraps TypeWrappedDeserializer only
    // ---

    /** The case-insensitively bound member's own type, carrying a constraint like {@link ConstrainedDto}. */
    static final class S1Child {
        @Size(max = 3)
        public String name;
    }

    /** Binds {@link S1Child} case-insensitively only through this member's own {@code @JsonFormat}. */
    static final class S1Holder {
        public String label;

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public S1Child child;
    }

    /**
     * S-1 (independent review, by-reading finding): {@code propertySchema}'s inline case-insensitive
     * path (around {@code InputPropertyDescriber} lines 733-735) computes {@code
     * unwrap(property.getValueDeserializer())} — {@code unwrap} strips only {@code
     * TypeWrappedDeserializer}, never a {@code DelegatingDeserializer} — before checking {@code
     * instanceof BeanDeserializerBase nestedBean && nestedBean.isCaseInsensitive()}. Under the same
     * mapper-wide wrapper as {@link #mapperWithForwardingWrapperForEveryBean()}, the member's own
     * contextual deserializer is still a {@code ForwardingDelegatingDeserializer}, so this check fails
     * and the member falls through to the ordinary reference path, describing it by {@code $ref} to the
     * type's plain, case-sensitive shared definition instead of inline with {@code patternProperties}.
     *
     * <p>Note (as directed): this pre-fix, ref-sharing description is <em>stricter</em> than the real
     * Jackson binder, which still binds the member case-insensitively regardless of how this description
     * is built — the gap is over-restriction of the generated schema against traffic the binder actually
     * accepts, not a validation bypass the way F2's or C-1's probes are.
     *
     * <p>Expected red (pre-fix): {@code document.path("properties").path("child")} carries a {@code $ref}
     * rather than an inline object with its own {@code patternProperties}.
     */
    @Test
    @DisplayName("S-1: under a mapper-wide DelegatingDeserializer wrapper, a member-level case-insensitive"
            + " child must still be described inline with patternProperties, not by $ref to the plain"
            + " (case-sensitive) shared definition")
    void memberLevelCaseInsensitiveChildUnderMapperWideWrapperIsDescribedInline() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(S1Holder.class, mapper);
        JsonNode child = document.path("properties").path("child");

        assertFalse(
                child.has("$ref"),
                "S-1 CURRENT STATE (pre-fix expected red): a member bound case-insensitively only through"
                        + " its own @JsonFormat must not fall back to the type's ordinary, case-sensitive"
                        + " $ref under the wrapper module — note the pre-fix ref-sharing behavior is"
                        + " stricter than the real binder (which binds case-insensitively regardless), not a"
                        + " bypass; document: " + document);
        assertEquals("object", child.path("type").asText(null), "document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "document: " + document);
        JsonNode childPatternProperties = child.path("patternProperties");
        assertTrue(
                childPatternProperties.isObject() && !childPatternProperties.isEmpty(),
                "S-1 DECISIVE: the inline nested description under the wrapper module must carry its own"
                        + " patternProperties, exactly as it does without the wrapper; document: " + document);
    }

    // --- C-2 (reopened, round 6 finding): a hidden member on an unwrapped child of an any-setter parent ---

    /**
     * A {@code @Schema(hidden = true)}-constrained unwrapped member — the same shape {@code
     * UnwrappedAnySetterFoldingTest.HiddenChild} probes without the wrapper.
     */
    static final class C2HiddenChild {
        @Schema(hidden = true)
        @Size(max = 3)
        public String token;
    }

    /** Carries {@link C2HiddenChild} through {@code @JsonUnwrapped} on an any-setter type. */
    static final class C2AnySetterParent {
        @JsonUnwrapped
        public C2HiddenChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * C-2 (reopened, round 6 finding): unlike the plain-property C-1 case above, a hidden member is
     * never published by the type's own class-level members — it can only reach the document through
     * {@code foldUnwrappedChildIntoParentPlan}, which runs only for a sibling the unwrapped-child loop
     * actually processed as a {@code BeanDeserializerBase}. Under the mapper-wide wrapper, the loop skips
     * {@link C2HiddenChild} for the same reason C-1's own Javadoc explains (the {@code instanceof
     * BeanDeserializerBase} check on {@code renamed} fails), so the fold never runs and {@code "token"} is
     * neither published nor reserved — falling through to the extras bucket unconstrained, exactly the
     * bypass F2 fixed for the unwrapped case.
     *
     * <p>Expected red now: {@code document.path("propertyNames").path("not").path("enum")} does not
     * contain {@code "token"}.
     */
    @Test
    @DisplayName("C-2: under a mapper-wide DelegatingDeserializer wrapper, an unwrapped child's hidden member"
            + " on an any-setter parent must still be reserved, not left to fall through to the extras bucket")
    void hiddenUnwrappedChildMemberIsReservedUnderMapperWideDelegatingWrapper() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();

        JsonNode document = document(C2AnySetterParent.class, mapper);

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
                "C-2 DECISIVE (expected red now): the unwrapped child's hidden member \"token\" must be"
                        + " reserved under the mapper-wide wrapper profile too, exactly as it is without the"
                        + " wrapper (UnwrappedAnySetterFoldingTest.unwrappedChildHiddenMemberIsReserved),"
                        + " refusing a key spelling it outright rather than letting it fall through to"
                        + " additionalProperties as an unconstrained extra; document: " + document);
    }

    // --- C-3 (reopened, round 6 finding): a case-insensitive unwrapped child is silently skipped, not refused ---

    /** Bound case-insensitively at the class level, so any position unwrapping it inherits that binding. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class C3CaseInsensitiveChild {
        public String name;
    }

    /** Carries {@link C3CaseInsensitiveChild} through a bare {@code @JsonUnwrapped}. */
    static final class C3Parent {
        @JsonUnwrapped
        public C3CaseInsensitiveChild child;
    }

    /**
     * C-3 control (no wrapper): {@code requireCaseSensitive} — called immediately after the loop's
     * {@code instanceof BeanDeserializerBase} check succeeds — refuses a case-insensitively bound
     * unwrapped child with a bounded diagnostic. This establishes the behavior the wrapper case below is
     * measured against.
     */
    @Test
    @DisplayName("C-3 control: without the wrapper, a case-insensitively bound unwrapped child is refused")
    void caseInsensitiveUnwrappedChildIsRefusedWithoutTheWrapper() {
        JsonMapperProfile profile = profile(new ObjectMapper());

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(C3Parent.class),
                "C-3 CONTROL: a case-insensitively bound unwrapped child must be refused, not silently"
                        + " described, when the loop actually reaches requireCaseSensitive");
        assertTrue(
                failure.getMessage().contains("bound case-insensitively"),
                "unexpected message: " + failure.getMessage());
    }

    /**
     * C-3 (reopened, round 6 finding): under the mapper-wide wrapper, {@link C3CaseInsensitiveChild}'s
     * root deserializer is itself a {@code ForwardingDelegatingDeserializer}, so {@code renamed} fails
     * the loop's {@code instanceof BeanDeserializerBase} check and the loop {@code continue}s <em>before</em>
     * ever reaching the {@code requireCaseSensitive} call a few lines later — the refusal this member
     * would otherwise trigger never runs, and generation succeeds silently instead, describing the parent
     * with no {@code "name"} property and no diagnostic at all: a soundness posture regression, not merely
     * a missing description, since the type is genuinely case-insensitively bound and no schema describes
     * that fact one way or the other.
     *
     * <p>Expected red now: generation does not throw, unlike the identical shape without the wrapper —
     * this test asserts the correct, refusing behavior (mirroring the control above) and therefore fails
     * pre-fix.
     */
    @Test
    @DisplayName("C-3: under a mapper-wide DelegatingDeserializer wrapper, a case-insensitively bound unwrapped"
            + " child must be refused, exactly as it is without the wrapper, not silently skipped")
    void caseInsensitiveUnwrappedChildIsNotRefusedUnderMapperWideDelegatingWrapper() {
        ObjectMapper mapper = mapperWithForwardingWrapperForEveryBean();
        JsonMapperProfile profile = profile(mapper);

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(C3Parent.class),
                "C-3 DECISIVE (expected red now): generation must refuse this shape exactly as it does"
                        + " without the wrapper (see the control above) — instead, under the wrapper the loop's"
                        + " instanceof BeanDeserializerBase check on \"renamed\" fails and continue runs before"
                        + " requireCaseSensitive is ever reached, so generation currently succeeds silently"
                        + " with the case-insensitively bound child's own property simply missing, and no"
                        + " diagnostic at all");
        assertTrue(
                failure.getMessage().contains("bound case-insensitively"),
                "unexpected message: " + failure.getMessage());
    }
}
