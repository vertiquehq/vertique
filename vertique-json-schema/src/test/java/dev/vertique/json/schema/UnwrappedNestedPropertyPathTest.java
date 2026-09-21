// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
import dev.vertique.json.JsonMapperProfiles;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * N1 (review finding, spike/deserializer-driven-schema round 7): commit dfd5761e made the
 * unwrapped-child loop in {@code InputPropertyDescriber#populateObjectSchema} (around lines 500-535)
 * refuse, with the custom-deserializer diagnostic, any declared {@code @JsonUnwrapped} member whose
 * {@code unwrappingDeserializer(...)} does not resolve to a {@code BeanDeserializerBase}.
 *
 * <p>For a member whose type is a {@code Map}, an abstract {@code @JsonTypeInfo} base, {@code
 * Object}, {@code JsonNode}, or {@code Optional<Bean>}, Jackson's own {@code
 * unwrappingDeserializer(...)} returns the <em>same instance</em> back — its documented signal that
 * the deserializer does not support unwrapping at all — and {@code BeanDeserializerBase.resolve()}
 * then leaves the member bound as an ordinary nested property, which the describer's own first
 * (non-unwrapped) property loop already publishes under the member's own name. The unwrapped-child
 * loop's {@code instanceof BeanDeserializerBase} check on {@code renamed} does not distinguish "same
 * instance, not a bean" (Jackson declining to unwrap) from "different instance, not a bean" (a
 * genuine custom deserializer that changes the wire shape, W-1's own probe in {@link
 * DelegatingDeserializerWrapperTest}) — it refuses both alike, so every DTO below, generatable
 * before dfd5761e, now fails at startup.
 *
 * <p>The correct bound is Jackson's own: refuse only when the unwrapping deserializer is a
 * <em>different</em> instance and is not a bean deserializer; when it is the same instance, leave
 * the member to the nested-property path the first loop already handles. This class only authors
 * the proof; the fix belongs in {@code InputPropertyDescriber} itself.
 */
class UnwrappedNestedPropertyPathTest {

    private static JsonMapperProfile plainProfile() {
        return JsonMapperProfiles.of(JsonProfileId.of("unwrapped-nested-property-path-test"), new ObjectMapper());
    }

    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(plainProfile()).generateCanonical(type));
    }

    // --- Map<String, Object> ---

    static final class MapAttrsParent {
        @JsonUnwrapped
        public Map<String, Object> attrs;
    }

    @Test
    @DisplayName("N1: an unwrapped Map<String, Object> member is published as an ordinary nested property, not"
            + " refused as an opaque custom deserializer")
    void unwrappedMapMemberIsPublishedAsNestedProperty() {
        JsonNode document = assertDoesNotThrow(
                () -> inputDocument(MapAttrsParent.class),
                "N1 DECISIVE: Jackson's MapDeserializer#unwrappingDeserializer(...) returns the same"
                        + " instance (it does not support unwrapping), so BeanDeserializerBase.resolve()"
                        + " leaves \"attrs\" bound as an ordinary nested property, which the describer's own"
                        + " first property loop already publishes — the unwrapped-child loop must not refuse"
                        + " this member merely because MapDeserializer is not a BeanDeserializerBase");
        assertTrue(
                document.path("properties").path("attrs").isObject(),
                "the member must be published as a nested property named \"attrs\"; document: " + document);
    }

    @Test
    @DisplayName("N1 premise: the real binder routes a nested {\"attrs\":{...}} body into the attrs field, not"
            + " flattened onto the parent, proving Map is genuinely left to the nested-property path")
    void jacksonBinderRoutesTheMapBodyIntoTheNestedAttrsField() throws Exception {
        MapAttrsParent bound = plainProfile().mapper().readValue("{\"attrs\":{\"k\":1}}", MapAttrsParent.class);

        assertTrue(
                bound.attrs != null && Integer.valueOf(1).equals(bound.attrs.get("k")),
                "N1 PREMISE: the real Jackson binder must bind the body's own \"attrs\" object into the"
                        + " member as an ordinary nested property (not flattened directly onto the parent),"
                        + " which is exactly why the describer's first loop — not the unwrapped-child loop —"
                        + " must be the one publishing it; bound.attrs=" + (bound.attrs == null ? null : bound.attrs));
    }

    // --- abstract @JsonTypeInfo base ---

    /** A closed polymorphic base, unwrapped rather than published as its own object. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Circle.class, name = "circle")})
    abstract static class AbstractShape {
        public String kind;
    }

    /** The only declared concrete subtype. */
    static final class Circle extends AbstractShape {
        public int radius;
    }

    static final class ShapeParent {
        @JsonUnwrapped
        public AbstractShape shape;
    }

    @Test
    @DisplayName("N1: an unwrapped abstract @JsonTypeInfo member is published as an ordinary nested property,"
            + " not refused as an opaque custom deserializer")
    void unwrappedPolymorphicBaseMemberIsPublishedAsNestedProperty() {
        JsonNode document = assertDoesNotThrow(
                () -> inputDocument(ShapeParent.class),
                "N1 DECISIVE: the polymorphic base's own type-id-resolving deserializer does not support"
                        + " unwrapping either, so \"shape\" must be left to the nested-property path rather"
                        + " than refused");
        assertTrue(
                document.path("properties").has("shape"),
                "the member must be published as a nested property named \"shape\"; document: " + document);
    }

    // --- Object ---

    static final class AnyParent {
        @JsonUnwrapped
        public Object any;
    }

    @Test
    @DisplayName("N1: an unwrapped Object member is published as an ordinary nested property, not refused as an"
            + " opaque custom deserializer")
    void unwrappedObjectMemberIsPublishedAsNestedProperty() {
        JsonNode document = assertDoesNotThrow(
                () -> inputDocument(AnyParent.class),
                "N1 DECISIVE: the untyped-object deserializer does not support unwrapping either, so \"any\""
                        + " must be left to the nested-property path rather than refused");
        assertTrue(
                document.path("properties").has("any"),
                "the member must be published as a nested property named \"any\"; document: " + document);
    }

    // --- JsonNode ---

    static final class NodeParent {
        @JsonUnwrapped
        public JsonNode node;
    }

    @Test
    @DisplayName("N1: an unwrapped JsonNode member is published as an ordinary nested property, not refused as"
            + " an opaque custom deserializer")
    void unwrappedJsonNodeMemberIsPublishedAsNestedProperty() {
        JsonNode document = assertDoesNotThrow(
                () -> inputDocument(NodeParent.class),
                "N1 DECISIVE: JsonNodeDeserializer does not support unwrapping either, so \"node\" must be"
                        + " left to the nested-property path rather than refused");
        assertTrue(
                document.path("properties").has("node"),
                "the member must be published as a nested property named \"node\"; document: " + document);
    }

    // --- Optional<Bean> ---

    static final class Child {
        public String name;
    }

    static final class OptionalParent {
        @JsonUnwrapped
        public Optional<Child> maybe;
    }

    @Test
    @DisplayName("N1: an unwrapped Optional<Bean> member is published as an ordinary nested property, not"
            + " refused as an opaque custom deserializer")
    void unwrappedOptionalMemberIsPublishedAsNestedProperty() {
        JsonNode document = assertDoesNotThrow(
                () -> inputDocument(OptionalParent.class),
                "N1 DECISIVE: Jackson's OptionalDeserializer does not support unwrapping either, so"
                        + " \"maybe\" must be left to the nested-property path rather than refused");
        assertTrue(
                document.path("properties").has("maybe"),
                "the member must be published as a nested property named \"maybe\"; document: " + document);
    }

    // --- The refusal branch itself: a genuinely non-unwrappable custom deserializer must still be refused ---

    /** An ordinary bean the shape-changing wrapper below still ultimately describes as an object. */
    static final class WireShapeChild {
        @Size(max = 3)
        public String name;
    }

    /**
     * Not a pure forwarder (the same shape {@code
     * DelegatingDeserializerWrapperTest.StringFormAcceptingDelegatingDeserializer} probes at the root
     * seam): overrides {@code deserialize} to accept a compact string form before ever reaching the
     * delegate, so {@code unwrappingDeserializer(...)} on it yields a genuinely different, non-bean
     * instance — the case the refusal branch exists for.
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
                WireShapeChild dto = new WireShapeChild();
                dto.name = parser.getValueAsString();
                return dto;
            }
            return super.deserialize(parser, ctxt);
        }
    }

    static final class WireShapeParent {
        @JsonUnwrapped
        public WireShapeChild child;
    }

    private static JsonMapperProfile shapeChangingWrapperProfile() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyDeserializer(
                            DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                        if (beanDesc.getBeanClass() == WireShapeChild.class) {
                            return new StringFormAcceptingDelegatingDeserializer(deserializer);
                        }
                        return deserializer;
                    }
                });
            }
        });
        return JsonMapperProfiles.of(JsonProfileId.of("unwrapped-nested-property-path-refusal-test"), mapper);
    }

    @Test
    @DisplayName("N1 refusal branch: an unwrapped member whose type has a non-pure DelegatingDeserializer"
            + " wrapper (changing the wire shape) is refused with the custom-deserializer diagnostic")
    void unwrappedMemberWithShapeChangingDelegatingWrapperIsRefused() {
        JsonMapperProfile profile = shapeChangingWrapperProfile();

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(WireShapeParent.class),
                "N1 REFUSAL BRANCH: a shape-changing DelegatingDeserializer subclass is a genuinely different"
                        + " instance from unwrappingDeserializer(...) and is not a bean deserializer, so this"
                        + " must still be refused, exactly as W-1 established at the root seam");
        assertTrue(
                failure.getMessage().contains("whose wire shape the generator cannot describe"),
                "unexpected message: " + failure.getMessage());
    }

    @Test
    @DisplayName("N1 refusal branch: the custom-deserializer diagnostic for an unwrapped member names the"
            + " member, not only the type")
    void refusalDiagnosticNamesTheUnwrappedMember() {
        JsonMapperProfile profile = shapeChangingWrapperProfile();

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(WireShapeParent.class));

        assertTrue(
                failure.getMessage().contains("child"),
                "the diagnostic must name the unwrapped member (\"child\"), not only the child type, so a"
                        + " caller with several unwrapped members on the same parent can tell which one is"
                        + " refused; was: " + failure.getMessage());
    }
}
