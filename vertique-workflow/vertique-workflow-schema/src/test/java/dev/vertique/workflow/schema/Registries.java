// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;

/** Workflow registries that do or do not carry the payload schema validator, over a shared bean validator. */
final class Registries {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Registries() {}

    /**
     * A registry carrying the schema validator under the reserved profile, with the constraint supplement enabled.
     *
     * @param cache the cache the validator fills
     * @return the registry
     */
    static DefaultWorkflowRegistry withSchemaValidator(PayloadSchemaCache cache) {
        return withSchemaValidator(cache, PayloadSchemaTestProfiles.builtIn(), PayloadSchemaTestProfiles.VERTIQUE);
    }

    /**
     * A registry carrying the schema validator under the given profile registry and configuration.
     *
     * @param cache the cache the validator fills
     * @param profiles the profile registry
     * @param config the configuration naming the system profile
     * @return the registry
     */
    static DefaultWorkflowRegistry withSchemaValidator(
            PayloadSchemaCache cache, JsonMapperProfileRegistry profiles, JsonConfig config) {
        return new DefaultWorkflowRegistry(
                null, Set.of(new PayloadSchemaRegistrationValidator(profiles, config, Optional.of(VALIDATOR), cache)));
    }

    /**
     * A registry carrying a schema validator whose payload classes load through {@code classLoader}.
     *
     * @param cache the cache the validator fills
     * @param classLoader the class loader used for registration-time resolution
     * @return the registry
     */
    static DefaultWorkflowRegistry withSchemaValidator(PayloadSchemaCache cache, ClassLoader classLoader) {
        PayloadSchemaGenerator generator = new PayloadSchemaGenerator(
                PayloadSchemaTestProfiles.builtIn(), PayloadSchemaTestProfiles.VERTIQUE, Optional.of(VALIDATOR));
        return new DefaultWorkflowRegistry(
                null, Set.of(new PayloadSchemaRegistrationValidator(cache, generator, classLoader)));
    }

    /**
     * A registry without the schema validator, as when the schema module is not installed.
     *
     * @return the registry
     */
    static DefaultWorkflowRegistry withoutSchemaValidator() {
        return new DefaultWorkflowRegistry();
    }

    /**
     * The canonical document the generator produces for {@code type} under the reserved profile with the same
     * constraint supplement the registries above enable, without going through the code under test.
     *
     * @param type the payload type
     * @return the parsed document
     */
    static JsonObject generated(Type type) {
        return new JsonObject(AnnotationJsonSchemaGenerator.forInputProfile(
                        PayloadSchemaTestProfiles.builtIn().profile(JsonProfileId.of("vertique")), VALIDATOR)
                .generateCanonical(type));
    }
}
