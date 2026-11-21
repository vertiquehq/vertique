// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.core.Response;
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

    /**
     * Target types probed for a decoder, and for an encoder whose response type is not known
     * statically. The declared parameter type is not available to the validator, so a media type
     * counts as supported when any decoder accepts it for any of these: the built-in decoders are
     * keyed on the target type as well as the media type, and no single type is accepted by all of
     * them.
     */
    private static final List<Class<?>> PROBE_TYPES = List.of(Object.class, String.class, Buffer.class, byte[].class);

    /** Types a value declared as a supertype may turn out to be at run time, beyond the declared type. */
    private static final List<Class<?>> RUNTIME_BODY_TYPES = List.of(String.class, Buffer.class, byte[].class);

    private MediaTypeValidator() {}

    /**
     * Validates that every declared {@code @Consumes} media type on the given methods has at
     * least one matching {@link RequestBodyDecoder}, and every declared {@code @Produces} media
     * type has at least one matching {@link ResponseBodyEncoder}.
     *
     * <p>A {@code @Consumes} type is checked with {@link RequestBodyDecoder#canDecode(Class, String)}
     * for each of {@code Object}, {@code String}, {@code Buffer} and {@code byte[]}, since the
     * parameter type is not known at startup; it is supported when any decoder accepts it for any
     * of those types.
     *
     * <p>A {@code @Produces} type is checked with {@link ResponseBodyEncoder#canEncode(Class, String)}
     * for the method's declared response type, plus {@code String}, {@code Buffer} and
     * {@code byte[]} when the declared type is a supertype of them, because a value of such a type
     * may be one of those at run time. A method whose response type is unknown ({@code Object},
     * {@code Response}, void or not recorded) is checked with the four types a decoder is.
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
                if (!anyDecoderSupports(decoders, type)) {
                    violations.add("Operation '" + method.operationId()
                            + "' declares @Consumes(\""
                            + type
                            + "\") but no RequestBodyDecoder supports it");
                }
            }

            for (String type : mediaTypes.produces()) {
                List<Class<?>> probeTypes = encoderProbeTypes(method.responseBodyType());
                if (!anyEncoderSupports(encoders, probeTypes, type)) {
                    violations.add("Operation '" + method.operationId()
                            + "' declares @Produces(\""
                            + type
                            + "\") but no ResponseBodyEncoder supports it"
                            + (probeTypes == PROBE_TYPES
                                    ? ""
                                    : " for response type " + probeTypes.get(0).getTypeName()));
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

    private static boolean anyDecoderSupports(List<RequestBodyDecoder> decoders, String type) {
        return decoders.stream().anyMatch(d -> PROBE_TYPES.stream().anyMatch(t -> d.canDecode(t, type)));
    }

    private static boolean anyEncoderSupports(
            List<ResponseBodyEncoder> encoders, List<Class<?>> probeTypes, String type) {
        return encoders.stream().anyMatch(e -> probeTypes.stream().anyMatch(t -> e.canEncode(t, type)));
    }

    /**
     * Returns the types to probe encoders with: the declared response type first, followed by the
     * body types a value of that declared type could be at run time. An unknown response type returns
     * {@link #PROBE_TYPES}, which callers detect by identity.
     */
    private static List<Class<?>> encoderProbeTypes(Class<?> responseType) {
        if (responseType == null
                || responseType == void.class
                || responseType == Void.class
                || Response.class.isAssignableFrom(responseType)) {
            return PROBE_TYPES;
        }
        List<Class<?>> types = new ArrayList<>();
        types.add(responseType);
        for (Class<?> runtimeType : RUNTIME_BODY_TYPES) {
            if (responseType != runtimeType && responseType.isAssignableFrom(runtimeType)) {
                types.add(runtimeType);
            }
        }
        return types;
    }
}
