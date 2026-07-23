// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.correlation.CorrelationHeaderValidator;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Configuration for {@link CorrelationIngressMiddleware}.
 *
 * <p>Default header names mirror the existing framework conventions ({@code X-Request-Id},
 * {@code X-Correlation-Id}, {@code X-Causation-Id}). Default echo policy preserves the
 * pre-correlation behaviour (echo {@code X-Request-Id}, do not echo {@code X-Correlation-Id} —
 * FR-COR-089 / FR-COR-090). Causation parsing is off by default per FR-COR-085 (MAY).
 *
 * <p>Configured header names MUST be valid HTTP header tokens — this record validates them at
 * construction via {@link CorrelationHeaderValidator#requireValidHeaderName(String)} so a typo
 * or an unsafe value fails fast at startup rather than at first response emission.
 *
 * <p>Records are Jackson-deserialisable; the framework loads this config from the
 * {@code "correlation.ingress"} section of the application config via
 * {@link dev.vertique.core.config.JsonConfigPaths#navigateObject}. Apps that need full
 * programmatic override can provide their own {@code @Provides @Singleton CorrelationIngressConfig}
 * — see {@link CorrelationIngressModule} for the lookup logic.
 *
 * @param requestIdHeader      HTTP header used to carry the request id (default {@code X-Request-Id})
 * @param correlationIdHeader  HTTP header used to carry the correlation id (default
 *                             {@code X-Correlation-Id})
 * @param causationIdHeader    HTTP header used to carry the causation id (default
 *                             {@code X-Causation-Id})
 * @param echoRequestId        emit the request id as a response header (default {@code true})
 * @param echoCorrelationId    emit the correlation id as a response header (default {@code false})
 * @param parseCausationId     accept an inbound causation id header (default {@code false})
 * @param invalidValuePolicy   policy applied when an inbound header value fails the header
 *                             validator's checks
 */
public record CorrelationIngressConfig(
        String requestIdHeader,
        String correlationIdHeader,
        String causationIdHeader,
        boolean echoRequestId,
        boolean echoCorrelationId,
        boolean parseCausationId,
        InvalidValuePolicy invalidValuePolicy) {

    /**
     * Compact validator: every header name MUST be a valid HTTP token per
     * {@link CorrelationHeaderValidator}; policy must be non-null.
     */
    public CorrelationIngressConfig {
        CorrelationHeaderValidator.requireValidHeaderName(requestIdHeader);
        CorrelationHeaderValidator.requireValidHeaderName(correlationIdHeader);
        CorrelationHeaderValidator.requireValidHeaderName(causationIdHeader);
        Objects.requireNonNull(invalidValuePolicy, "invalidValuePolicy");
    }

    /**
     * Jackson-friendly factory that fills in defaults for any omitted JSON properties. Used by
     * the {@code @Provides CorrelationIngressConfig} in {@link CorrelationIngressModule} when
     * mapping the {@code "correlation.ingress"} section to this record.
     *
     * @param requestIdHeader     header name; defaults to {@code "X-Request-Id"} when {@code null}
     * @param correlationIdHeader header name; defaults to {@code "X-Correlation-Id"} when {@code null}
     * @param causationIdHeader   header name; defaults to {@code "X-Causation-Id"} when {@code null}
     * @param echoRequestId       echo request id; defaults to {@code true} when {@code null}
     * @param echoCorrelationId   echo correlation id; defaults to {@code false} when {@code null}
     * @param parseCausationId    parse causation id; defaults to {@code false} when {@code null}
     * @param invalidValuePolicy  policy; defaults to {@code REPLACE_WITH_GENERATED} when {@code null}
     * @return the deserialised config
     */
    @JsonCreator
    public static CorrelationIngressConfig fromJson(
            @JsonProperty("requestIdHeader") @Nullable String requestIdHeader,
            @JsonProperty("correlationIdHeader") @Nullable String correlationIdHeader,
            @JsonProperty("causationIdHeader") @Nullable String causationIdHeader,
            @JsonProperty("echoRequestId") @Nullable Boolean echoRequestId,
            @JsonProperty("echoCorrelationId") @Nullable Boolean echoCorrelationId,
            @JsonProperty("parseCausationId") @Nullable Boolean parseCausationId,
            @JsonProperty("invalidValuePolicy") @Nullable InvalidValuePolicy invalidValuePolicy) {
        CorrelationIngressConfig d = defaults();
        return new CorrelationIngressConfig(
                requestIdHeader != null ? requestIdHeader : d.requestIdHeader,
                correlationIdHeader != null ? correlationIdHeader : d.correlationIdHeader,
                causationIdHeader != null ? causationIdHeader : d.causationIdHeader,
                echoRequestId != null ? echoRequestId : d.echoRequestId,
                echoCorrelationId != null ? echoCorrelationId : d.echoCorrelationId,
                parseCausationId != null ? parseCausationId : d.parseCausationId,
                invalidValuePolicy != null ? invalidValuePolicy : d.invalidValuePolicy);
    }

    /**
     * Decides how the ingress middleware reacts to an inbound header value that fails the
     * {@link dev.vertique.core.correlation.CorrelationHeaderValidator} checks.
     */
    public enum InvalidValuePolicy {
        /** Reject the request (4xx). Suitable for strict environments. */
        REJECT,
        /** Replace the inbound value with a freshly generated one (default — FR-COR-053). */
        REPLACE_WITH_GENERATED
    }

    /**
     * Default configuration: {@code X-Request-Id}/{@code X-Correlation-Id}/{@code X-Causation-Id}
     * with request-id echo enabled, correlation-id echo disabled, causation parsing off, and
     * invalid values replaced with generated ones.
     *
     * @return the default config; never {@code null}
     */
    public static CorrelationIngressConfig defaults() {
        return new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                false,
                InvalidValuePolicy.REPLACE_WITH_GENERATED);
    }
}
