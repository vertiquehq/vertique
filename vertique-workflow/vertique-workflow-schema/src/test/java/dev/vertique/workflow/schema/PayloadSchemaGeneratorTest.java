// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.workflow.schema.fixtures.Payloads;
import jakarta.validation.Validation;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the generator: the profile it describes under, when that profile is resolved, and how it fails. */
class PayloadSchemaGeneratorTest {

    @Test
    @DisplayName("describe returns the canonical document the schema generator emits under the configured profile")
    void describeReturnsTheCanonicalDocumentUnderTheConfiguredProfile() {
        // Given: a generator for the reserved vertique profile
        PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                PayloadSchemaTestProfiles.builtIn(), PayloadSchemaTestProfiles.VERTIQUE, Optional.empty());

        // When / Then: it describes exactly what the schema generator emits for that profile
        assertThat(generator.profileId()).isEqualTo(JsonProfileId.of("vertique"));
        assertThat(generator.describe(Payloads.PlainRecord.class)).isEqualTo(Documents.of(Payloads.PlainRecord.class));
        assertThat(generator.describe(Payloads.RequiredAliasRecord.class))
                .isEqualTo(Documents.of(Payloads.RequiredAliasRecord.class));
    }

    @Test
    @DisplayName("describe follows the configured profile's mapper")
    void describeFollowsTheConfiguredProfilesMapper() {
        // Given: a generator for a profile with snake case naming
        PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                PayloadSchemaTestProfiles.with(PayloadSchemaTestProfiles.snakeCase("p1")),
                PayloadSchemaTestProfiles.systemProfile("p1"),
                Optional.empty());

        // When / Then: the wire names are the profile's
        assertThat(generator.profileId()).isEqualTo(JsonProfileId.of("p1"));
        assertThat(generator
                        .describe(Payloads.Comment.class)
                        .getJsonObject("properties")
                        .fieldNames())
                .containsExactly("display_name");
    }

    @Test
    @DisplayName("the default system profile is used when the configuration names none")
    void theDefaultSystemProfileIsUsedWhenTheConfigurationNamesNone() {
        // Given: the default configuration
        PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                PayloadSchemaTestProfiles.builtIn(), dev.vertique.json.JsonConfig.defaults(), Optional.empty());

        // Then: the profile id is the system profile
        assertThat(generator.profileId()).isEqualTo(JsonProfileId.SYSTEM);
    }

    @Test
    @DisplayName("describe works with a bean validator bound")
    void describeWorksWithABeanValidatorBound() throws Exception {
        // Given: a generator that also reads bean validation metadata
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                    PayloadSchemaTestProfiles.builtIn(),
                    PayloadSchemaTestProfiles.VERTIQUE,
                    Optional.of(factory.getValidator()));

            // When / Then: constraints appear in the document
            assertThat(generator
                            .describe(Payloads.GoodPattern.class)
                            .getJsonObject("properties")
                            .getJsonObject("code")
                            .getString("pattern"))
                    .isEqualTo("^[a-z]+$");
        }
    }

    @Test
    @DisplayName("the profile id is fixed at construction, the profile itself is resolved at first description")
    void theProfileIsResolvedLazily() {
        // Given: a configuration naming a profile the registry does not hold
        PayloadSchemaGenerator[] holder = new PayloadSchemaGenerator[1];

        // When / Then: construction and the id succeed
        assertThatCode(() -> holder[0] = new PayloadSchemaGenerator(
                        PayloadSchemaTestProfiles.builtIn(),
                        PayloadSchemaTestProfiles.systemProfile("nope"),
                        Optional.empty()))
                .doesNotThrowAnyException();
        assertThat(holder[0].profileId()).isEqualTo(JsonProfileId.of("nope"));

        // And: the first description reports the unknown profile, and so does every later one
        assertThatThrownBy(() -> holder[0].describe(Payloads.PlainRecord.class))
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> holder[0].describe(Payloads.PlainRecord.class))
                .isInstanceOf(JsonProfileConfigurationException.class);
    }

    @Test
    @DisplayName("a profile the schema generator refuses to be built from fails the description")
    void aRefusedProfileFailsTheDescription() {
        // Given: a profile that declares a null type override
        PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                PayloadSchemaTestProfiles.with(PayloadSchemaTestProfiles.withNullOverride("bad")),
                PayloadSchemaTestProfiles.systemProfile("bad"),
                Optional.empty());

        // When / Then: describing fails with a runtime exception naming the profile
        assertThatThrownBy(() -> generator.describe(Payloads.PlainRecord.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("profile 'bad'");
    }
}
