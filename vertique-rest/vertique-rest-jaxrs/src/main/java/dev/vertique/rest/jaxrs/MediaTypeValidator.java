// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Validates that declared {@code @Consumes} and {@code @Produces} media types on JAX-RS
 * resource methods have matching {@link RequestBodyDecoder} or {@link ResponseBodyEncoder}
 * registrations.
 *
 * <p>Called during router initialization after all resource methods have been scanned.
 * Three validation modes are supported, controlled by the {@code mediaTypeValidation}
 * parameter:
 * <ul>
 *   <li>{@code "WARN"} — log a warning for each unsupported media type; startup continues</li>
 *   <li>{@code "STRICT"} — throw a {@link RestConfigurationException} listing all violations</li>
 *   <li>{@code "OFF"} — skip validation entirely</li>
 * </ul>
 *
 * <p>Methods with empty {@code consumes} or {@code produces} lists (i.e. unconstrained) are
 * skipped — no decoder or encoder constraint is implied by the absence of annotations.
 */
@Slf4j
class MediaTypeValidator {

    private MediaTypeValidator() {}

    /**
     * Validates that every declared {@code @Consumes} media type on the given methods has at
     * least one matching {@link RequestBodyDecoder}, and every declared {@code @Produces} media
     * type has at least one matching {@link ResponseBodyEncoder}.
     *
     * <p>Validation is performed by calling {@link RequestBodyDecoder#canDecode(Class, String)}
     * and {@link ResponseBodyEncoder#canEncode(Class, String)} with {@code Object.class} as the
     * target type, since the actual parameter or return type is irrelevant for media-type support
     * checks at startup.
     *
     * <p>When {@code mode} is {@code "OFF"} (case-insensitive), this method returns immediately
     * without performing any checks.
     *
     * @param methods  the list of discovered resource method metadata to validate
     * @param decoders priority-sorted list of registered request body decoders
     * @param encoders priority-sorted list of registered response body encoders
     * @param mode     validation mode: {@code "WARN"}, {@code "STRICT"}, or {@code "OFF"}
     * @throws RestConfigurationException if {@code mode} is {@code "STRICT"} and any violations
     *                                    are found
     */
    static void validate(
            List<ResourceMethodMeta> methods,
            List<RequestBodyDecoder> decoders,
            List<ResponseBodyEncoder> encoders,
            String mode) {
        if ("OFF".equalsIgnoreCase(mode)) {
            return;
        }

        List<String> violations = new ArrayList<>();

        for (ResourceMethodMeta method : methods) {
            ResourceMethodMeta.MediaTypes mediaTypes = method.mediaTypes();
            if (mediaTypes == null) {
                continue;
            }

            for (String type : mediaTypes.consumes()) {
                if (decoders.stream().noneMatch(d -> d.canDecode(Object.class, type))) {
                    violations.add("Operation '" + method.operationId()
                            + "' declares @Consumes(\""
                            + type
                            + "\") but no RequestBodyDecoder supports it");
                }
            }

            for (String type : mediaTypes.produces()) {
                if (encoders.stream().noneMatch(e -> e.canEncode(Object.class, type))) {
                    violations.add("Operation '" + method.operationId()
                            + "' declares @Produces(\""
                            + type
                            + "\") but no ResponseBodyEncoder supports it");
                }
            }
        }

        if (violations.isEmpty()) {
            return;
        }

        if ("STRICT".equalsIgnoreCase(mode)) {
            throw new RestConfigurationException("Media type validation failed:\n  " + String.join("\n  ", violations));
        }

        violations.forEach(v -> log.warn("Media type mismatch: {}", v));
    }
}
