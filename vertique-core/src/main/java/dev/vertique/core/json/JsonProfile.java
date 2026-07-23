// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects a named JSON mapper {@link JsonProfileId profile} for a framework JSON boundary. This
 * is the single per-binding JSON profile selector across all framework JSON boundaries (ADR-0138):
 *
 * <ul>
 *   <li><strong>REST server resources</strong> — placed on a JAX-RS resource TYPE or METHOD;
 *       method-level overrides class-level. Controls request-body (de)serialization and response
 *       serialization.
 *   <li><strong>rest-client interfaces</strong> — placed on a {@code @RestClient}-annotated
 *       interface TYPE. Method-level placement is rejected at build time (FR-JSON-066).
 *   <li><strong>{@code @KafkaListener} / {@code @KafkaProducer} types</strong> — placed on a
 *       Kafka listener or producer TYPE. Method-level placement is rejected at build time
 *       (FR-JSON-066).
 * </ul>
 *
 * <p>When the annotation is absent the boundary falls back to its per-boundary configured default,
 * then the global {@code json.jsonProfile} config key, and ultimately to the reserved {@code vertx}
 * profile. The referenced profile must be registered in the {@link JsonMapperProfileRegistry},
 * otherwise resolution fails at startup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface JsonProfile {

    /**
     * The id of the JSON mapper profile to apply at the annotated boundary.
     *
     * @return the profile id value (matched against {@link JsonProfileId#value()})
     */
    String value();
}
