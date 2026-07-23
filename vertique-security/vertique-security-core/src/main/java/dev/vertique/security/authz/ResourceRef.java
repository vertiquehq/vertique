// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable reference to a protected resource that is the target of an authorization check.
 *
 * <p>A {@code ResourceRef} identifies the resource type and a specific instance (or all instances
 * of the type when {@code id} is the empty string), together with optional context attributes that
 * a policy evaluator may use to refine the decision.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code type} is required (non-null, non-blank)</li>
 *   <li>{@code id} is required (non-null) but MAY be the empty string, which means "any resource
 *       of this type" — useful for pre-flight permission checks before a specific instance is known</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The {@code attributes} map is defensively copied</li>
 * </ul>
 *
 * @param type       the resource type (e.g., {@code "order"}, {@code "payment"}); must not be blank
 * @param id         the specific resource instance identifier; empty string means "any instance of
 *                   this type"
 * @param attributes additional resource-level attributes for policy evaluation context
 */
public record ResourceRef(String type, String id, Map<String, Object> attributes) {

    /**
     * Compact constructor — validates required fields and defensively copies the attributes map.
     */
    public ResourceRef {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        Objects.requireNonNull(id, "id");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
