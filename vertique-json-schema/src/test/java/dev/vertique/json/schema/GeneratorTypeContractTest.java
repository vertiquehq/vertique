// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AnnotationJsonSchemaGenerator}'s resolved {@link Type} grammar and the bounds every
 * rejection diagnostic honours.
 *
 * <p>Accepted forms are proven by generating a document for each; rejected forms are proven by the
 * bounded {@link JsonSchemaGenerationException} they raise. Every rejection message is additionally
 * checked against the diagnostics contract: at most 512 UTF-16 code units in total, at most 256 for
 * one resolved-type identity, and never any fixture value.
 */
class GeneratorTypeContractTest {

    /** Neutral mapper used to prove each accepted form produced a readable document. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Every accepted Type form generates a document")
    void acceptsClassPrimitiveArrayParameterizedOwnerAndGenericArrayTypes() throws Exception {
        // Given: one instance of every accepted form in the grammar.
        Map<String, Type> accepted = new LinkedHashMap<>();
        accepted.put("plain class", HardeningFixtures.SimpleDto.class);
        accepted.put("primitive class", int.class);
        accepted.put("array class", String[].class);
        accepted.put("raw generic class", List.class);
        accepted.put("parameterized type", ProofFixtures.parameterized(List.class, String.class));
        accepted.put(
                "nested parameterized type",
                ProofFixtures.parameterized(
                        Map.class, String.class, ProofFixtures.parameterized(List.class, Integer.class)));
        accepted.put("generic array type", HardeningFixtures.holderType("matrix"));
        accepted.put("owner-bearing parameterized type", HardeningFixtures.holderType("nested"));

        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        for (Map.Entry<String, Type> form : accepted.entrySet()) {
            // When: the default-mode generator is asked for a canonical document.
            String canonical = generator.generateCanonical(form.getValue());

            // Then: a readable document is produced.
            assertNotNull(canonical, "the " + form.getKey() + " form must produce a document");
            assertNotNull(MAPPER.readTree(canonical), "the " + form.getKey() + " document must be readable JSON");
        }
    }

    @Test
    @DisplayName("Generic array and owner-bearing fixtures really are the reflection forms they claim")
    void acceptedFixturesCarryTheIntendedReflectionForms() {
        // Given/Then: the generic-array fixture is a GenericArrayType whose component is parameterized.
        Type matrix = HardeningFixtures.holderType("matrix");
        assertInstance(matrix, java.lang.reflect.GenericArrayType.class, "matrix");
        assertInstance(
                ((java.lang.reflect.GenericArrayType) matrix).getGenericComponentType(),
                ParameterizedType.class,
                "matrix component");

        // Given/Then: the nested fixture is a ParameterizedType with a non-null owner type.
        Type nested = HardeningFixtures.holderType("nested");
        assertInstance(nested, ParameterizedType.class, "nested");
        assertNotNull(((ParameterizedType) nested).getOwnerType(), "the owner-type fixture must carry an owner type");
    }

    @Test
    @DisplayName("Null, variables, wildcards, nested unresolved forms, and unknown Type impls are rejected")
    void rejectsNullTypeVariableWildcardNestedUnresolvedAndUnknownTypeImpls() {
        // Given: one instance of every rejected form.
        Map<String, Type> rejected = new LinkedHashMap<>();
        rejected.put("null", null);
        rejected.put("type variable", HardeningFixtures.Generic.class.getTypeParameters()[0]);
        rejected.put("wildcard", wildcardArgumentOfWildcardsField());
        rejected.put("parameterized type containing a wildcard", HardeningFixtures.holderType("wildcards"));
        rejected.put("unknown Type implementation", unknownTypeImplementation());

        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        for (Map.Entry<String, Type> form : rejected.entrySet()) {
            // When: generation is attempted.
            JsonSchemaGenerationException failure = assertThrows(
                    JsonSchemaGenerationException.class,
                    () -> generator.generateCanonical(form.getValue()),
                    "the " + form.getKey() + " form must be rejected");

            // Then: the diagnostic is bounded and carries no fixture value.
            assertBoundedAndValueFree(failure.getMessage(), form.getKey());
        }
    }

    @Test
    @DisplayName("A pathologically long type name is truncated inside a still-bounded message")
    void rejectionTruncatesAPathologicallyLongTypeIdentity() {
        // Given: a known reflection form whose reported type name is far longer than the identity bound.
        String longName = "dev.vertique.fixture.Pathological".repeat(20);
        assertTrue(longName.length() > 300, "the synthetic type name must exceed the identity bound");
        Type pathological = pathologicalParameterizedType(longName);

        // When: generation is attempted (the type carries an unresolved argument).
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(pathological));

        // Then: the whole message stays bounded and the identity portion is truncated.
        String message = failure.getMessage();
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was " + message.length());
        assertFalse(message.contains(longName), "the untruncated type name must not reach the message");
        String truncated = longName.substring(0, Diagnostics.MAX_TYPE_IDENTITY_LENGTH - 3) + "...";
        assertEquals(
                Diagnostics.MAX_TYPE_IDENTITY_LENGTH,
                truncated.length(),
                "the truncated identity must be exactly bounded");
        assertTrue(message.contains(truncated), "the message must carry the truncated identity; was: " + message);
    }

    @Test
    @DisplayName("Both profile factories reject a null profile with NullPointerException(\"profile\")")
    void forProfileFactoriesThrowNpeWithProfileMessageOnNull() {
        // Given/When/Then: the input factory names the offending parameter.
        NullPointerException input =
                assertThrows(NullPointerException.class, () -> AnnotationJsonSchemaGenerator.forInputProfile(null));
        assertEquals("profile", input.getMessage(), "forInputProfile(null) must name the profile parameter");

        // Given/When/Then: the output factory does the same.
        NullPointerException output =
                assertThrows(NullPointerException.class, () -> AnnotationJsonSchemaGenerator.forOutputProfile(null));
        assertEquals("profile", output.getMessage(), "forOutputProfile(null) must name the profile parameter");
    }

    // --- Helpers ---

    /**
     * Asserts that a rejection message honours the bounded, value-free diagnostics contract.
     *
     * @param message the failure message
     * @param form    the rejected form's label, used in assertion messages
     */
    private static void assertBoundedAndValueFree(String message, String form) {
        assertNotNull(message, "the " + form + " rejection must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the " + form + " message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertFalse(
                message.contains(HardeningFixtures.SENTINEL_VALUE),
                "the " + form + " message must not echo a fixture value");
    }

    /**
     * Asserts an object is an instance of the expected reflection form.
     *
     * @param actual   the value to classify
     * @param expected the expected form
     * @param label    the fixture label used in the assertion message
     */
    private static void assertInstance(Object actual, Class<?> expected, String label) {
        assertTrue(
                expected.isInstance(actual),
                "the " + label + " fixture must be a " + expected.getSimpleName() + "; was " + actual);
    }

    /**
     * Extracts the {@code ?} argument of the {@code List<?>} fixture field.
     *
     * @return the wildcard type
     */
    private static Type wildcardArgumentOfWildcardsField() {
        return ((ParameterizedType) HardeningFixtures.holderType("wildcards")).getActualTypeArguments()[0];
    }

    /**
     * Builds a {@code Type} implementation this package does not recognise.
     *
     * @return the unknown form
     */
    private static Type unknownTypeImplementation() {
        return new Type() {

            @Override
            public String getTypeName() {
                return "an entirely custom type form";
            }
        };
    }

    /**
     * Builds a known reflection form — a {@link ParameterizedType} — whose reported type name is
     * pathologically long and whose argument is unresolved, so it is rejected while forcing the
     * identity bound to apply.
     *
     * @param typeName the pathological type name
     * @return the parameterized type
     */
    private static Type pathologicalParameterizedType(String typeName) {
        return new ParameterizedType() {

            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {HardeningFixtures.Generic.class.getTypeParameters()[0]};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String getTypeName() {
                return typeName;
            }
        };
    }
}
