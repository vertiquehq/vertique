// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonMapperProfiles;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression proof for profile-mapper property names in generated input and output schemas. */
class ProfileMapperPropertyNamingTest {

    record WeatherRequest(String locationName) {}

    record WeatherResult(String locationName, int temperatureCelsius) {}

    static class GenericProfileBase<T> {
        public T inheritedTotal;
    }

    static final class DirectionalAmount extends GenericProfileBase<BigDecimal> {
        public BigDecimal amountTotal;

        private String acceptedOnly;
        private String emittedOnly;
        private String explicitName;

        public String getAcceptedOnly() {
            return acceptedOnly;
        }

        public void setAcceptedOnly(String acceptedOnly) {
            this.acceptedOnly = acceptedOnly;
        }

        public String getEmittedOnly() {
            return emittedOnly;
        }

        public void setEmittedOnly(String emittedOnly) {
            this.emittedOnly = emittedOnly;
        }

        public String getExplicitName() {
            return explicitName;
        }

        public void setExplicitName(String explicitName) {
            this.explicitName = explicitName;
        }
    }

    abstract static class DirectionalAmountMixin {
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        abstract void setAcceptedOnly(String value);

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        abstract String getEmittedOnly();

        @JsonProperty("profile_explicit")
        abstract String getExplicitName();

        @JsonProperty("profile_explicit")
        abstract void setExplicitName(String value);
    }

    @Test
    void shouldUseTheProfileMappersPropertyNamesInBothSchemaDirections() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JsonMapperProfile profile = JsonMapperProfiles.of(JsonProfileId.of("snake-case-test"), mapper);

        JsonNode input = mapper.readTree(
                AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(WeatherRequest.class));
        JsonNode output = mapper.readTree(
                AnnotationJsonSchemaGenerator.forOutputProfile(profile).generateCanonical(WeatherResult.class));

        assertFalse(input.at("/properties/location_name").isMissingNode(), input::toPrettyString);
        assertFalse(input.path("properties").has("locationName"), input::toPrettyString);
        assertFalse(output.at("/properties/location_name").isMissingNode(), output::toPrettyString);
        assertFalse(output.at("/properties/temperature_celsius").isMissingNode(), output::toPrettyString);
        assertFalse(output.path("properties").has("locationName"), output::toPrettyString);
        assertFalse(output.path("properties").has("temperatureCelsius"), output::toPrettyString);
    }

    @Test
    void shouldPreserveMixinDirectionNamingAndTypeOverridesTogether() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.addMixIn(DirectionalAmount.class, DirectionalAmountMixin.class);
        JsonMapperProfile profile = new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("directional-mixin-test");
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of(JsonSchemaTypeOverride.both(
                        BigDecimal.class, HardeningFixtures.markerFragment("profile-decimal")));
            }
        };

        JsonNode input = mapper.readTree(
                AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(DirectionalAmount.class));
        JsonNode output = mapper.readTree(
                AnnotationJsonSchemaGenerator.forOutputProfile(profile).generateCanonical(DirectionalAmount.class));

        DirectionalAmount decoded = mapper.readValue(
                "{\"accepted_only\":\"accepted\",\"emitted_only\":\"ignored\"}", DirectionalAmount.class);
        assertEquals("accepted", decoded.getAcceptedOnly());
        assertNull(decoded.getEmittedOnly());
        DirectionalAmount encoded = new DirectionalAmount();
        encoded.setAcceptedOnly("hidden");
        encoded.setEmittedOnly("emitted");
        JsonNode encodedTree = mapper.valueToTree(encoded);
        assertFalse(encodedTree.has("accepted_only"), encodedTree::toPrettyString);
        assertEquals("emitted", encodedTree.path("emitted_only").asText());

        assertTrue(input.path("properties").has("accepted_only"), input::toPrettyString);
        assertFalse(input.path("properties").has("emitted_only"), input::toPrettyString);
        assertTrue(output.path("properties").has("emitted_only"), output::toPrettyString);
        assertFalse(output.path("properties").has("accepted_only"), output::toPrettyString);
        assertTrue(input.path("properties").has("profile_explicit"), input::toPrettyString);
        assertTrue(output.path("properties").has("profile_explicit"), output::toPrettyString);
        assertTrue(input.path("properties").has("amount_total"), input::toPrettyString);
        assertTrue(output.path("properties").has("amount_total"), output::toPrettyString);
        assertTrue(input.path("properties").has("inherited_total"), input::toPrettyString);
        assertTrue(output.path("properties").has("inherited_total"), output::toPrettyString);
        assertEquals(
                "#/$defs/BigDecimal",
                input.at("/properties/inherited_total/$ref").asText(),
                input::toPrettyString);
        assertEquals(
                "#/$defs/BigDecimal",
                output.at("/properties/inherited_total/$ref").asText(),
                output::toPrettyString);
        assertEquals("profile-decimal", input.at("/$defs/BigDecimal/format").asText(), input::toPrettyString);
        assertEquals("profile-decimal", output.at("/$defs/BigDecimal/format").asText(), output::toPrettyString);
        assertTrue(input.toString().contains("profile-decimal"), input::toPrettyString);
        assertTrue(output.toString().contains("profile-decimal"), output::toPrettyString);
    }
}
