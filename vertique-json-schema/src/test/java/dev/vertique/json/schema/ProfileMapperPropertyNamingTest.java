// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import org.junit.jupiter.api.Test;

/** Regression proof for profile-mapper property names in generated input and output schemas. */
class ProfileMapperPropertyNamingTest {

    record WeatherRequest(String locationName) {}

    record WeatherResult(String locationName, int temperatureCelsius) {}

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
}
