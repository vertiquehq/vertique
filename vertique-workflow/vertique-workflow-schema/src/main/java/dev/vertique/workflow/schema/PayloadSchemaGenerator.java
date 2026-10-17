// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import io.vertx.core.json.JsonObject;
import jakarta.validation.Validator;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Describes payload types as canonical Draft 2020-12 documents under the process JSON profile.
 *
 * <p>The profile id is fixed at construction from the configuration; the profile itself is resolved at the first
 * description, so a configuration that names an unknown profile fails there rather than when the module is wired.
 * The underlying schema generator is built once, published through a concurrent map so a failed construction is
 * retried, and reads bean validation metadata when a validator is supplied. Whether that supplement is enabled is
 * logged once per generator.
 */
class PayloadSchemaGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(PayloadSchemaGenerator.class);

    private final JsonMapperProfileRegistry profileRegistry;
    private final JsonProfileId profileId;
    private final Validator validator;
    private final ConcurrentHashMap<JsonProfileId, AnnotationJsonSchemaGenerator> generators =
            new ConcurrentHashMap<>();

    PayloadSchemaGenerator(
            JsonMapperProfileRegistry profileRegistry, JsonConfig jsonConfig, Optional<Validator> validator) {
        this.profileRegistry = profileRegistry;
        this.profileId = jsonConfig.effectiveSystemProfile();
        this.validator = validator.orElse(null);
    }

    /**
     * The profile this generator describes under.
     *
     * @return the effective system profile id
     */
    JsonProfileId profileId() {
        return profileId;
    }

    /**
     * Describes a type.
     *
     * @param type the resolved payload type
     * @return the canonical document
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the configured profile is unknown
     * @throws RuntimeException if the generator cannot be built or the type cannot be described
     */
    JsonObject describe(Type type) {
        return new JsonObject(
                generators.computeIfAbsent(profileId, this::create).generateCanonical(type));
    }

    private AnnotationJsonSchemaGenerator create(JsonProfileId id) {
        JsonMapperProfile profile = profileRegistry.profile(id);
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(profile, validator);
        LOG.info("workflow payload schema constraint supplement: {}", validator == null ? "disabled" : "enabled");
        return generator;
    }
}
