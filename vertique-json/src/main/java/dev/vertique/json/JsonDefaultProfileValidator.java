// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.lifecycle.ComposeValidator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link ComposeValidator} that fails the application's {@code VALIDATE} phase when the configured
 * managed-edge default profile ({@code json.jsonProfile}) names a profile id that the
 * {@link JsonMapperProfileRegistry} does not know.
 *
 * <p>The validation is performed in the {@code @Inject} constructor (the
 * constructible-as-validation pattern) and is a pure delegate to
 * {@link JsonMapperProfileRegistry#validateConfigured(String)} — the registry owns every message,
 * including the one naming the {@code vertx} → {@code system} rename. When
 * {@code json.jsonProfile} is non-null and non-blank, the constructor resolves it through the
 * registry; an unknown id — and the retired {@code vertx} id — throws
 * {@link JsonProfileConfigurationException}. A {@code null}/blank id is a no-op (the reserved
 * {@code vertique} floor applies).
 *
 * <p>The second key, {@code json.systemProfile}, is validated here as well (same registry rules: the
 * id must be registered and must not be the retired {@code vertx}). Its consumer — the process-codec
 * install step at the {@code CONFIGURE} phase — resolves the same id earlier and fails startup on a
 * mis-set value; this validator repeats the registry check at {@code VALIDATE} so the failure is
 * recorded with the other compose-validation results, and re-checks the installed process mapper for
 * default typing after installation.
 */
@Singleton
public final class JsonDefaultProfileValidator implements ComposeValidator {

    /**
     * Validates the configured global default profile id against the registry.
     *
     * <p>When {@code config.jsonProfile()} is non-null and non-blank, resolves it through the
     * registry; an unregistered id — and the retired {@code vertx} id — throws
     * {@link JsonProfileConfigurationException}, failing fast at construction (the {@code VALIDATE}
     * phase). A {@code null}/blank id is a no-op — the reserved {@code vertique} floor applies.
     *
     * @param config the parsed {@code json} configuration section
     * @param registry the JSON mapper profile registry used to resolve the configured default id
     * @throws JsonProfileConfigurationException if {@code config.jsonProfile()} or
     *     {@code config.systemProfile()} names an unregistered or retired profile id
     */
    @Inject
    public JsonDefaultProfileValidator(JsonConfig config, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(config.jsonProfile());
        registry.validateConfigured(config.systemProfile());
        // Security review: the install seam checks the mapper it is handed, but the installed
        // mapper stays a live ObjectMapper. Re-check one phase later so default typing activated
        // on it during CONFIGURE fails the boot instead of serving.
        if (VertiqueJson.ownsCodec()
                && VertiqueJson.profile().isPresent()
                && VertiqueJson.mapper().getDeserializationConfig().getDefaultTyper(null) != null) {
            throw new JsonProfileConfigurationException("the process JSON codec's mapper (profile '"
                    + VertiqueJson.profile().get().value()
                    + "') had Jackson default typing activated after installation; default typing is not"
                    + " allowed on the process codec");
        }
    }
}
