// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.List;
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
}
