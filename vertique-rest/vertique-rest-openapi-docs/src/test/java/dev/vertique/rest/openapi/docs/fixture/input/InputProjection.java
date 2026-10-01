// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The facts of one binding-inventory entry a document's Parameter Objects are compared with: where
 * the input comes from, its location, name, requiredness, default, and the description of its
 * declared {@code @Parameter}.
 *
 * @param origin       where the input comes from
 * @param location     the input's location, {@code null} for the body
 * @param name         the bound name, {@code null} for the body
 * @param requiredness whether the runtime rejects a missing value
 * @param defaultValue the declared {@code @DefaultValue}, or {@code null}
 * @param description  the description of the input's {@code @Parameter}, or {@code null} when it
 *                     has none or a blank one
 */
public record InputProjection(
        Origin origin,
        @Nullable ParamLocation location,
        @Nullable String name,
        Requiredness requiredness,
        @Nullable String defaultValue,
        @Nullable String description) {

    /**
     * Projects one inventory entry.
     *
     * @param binding the entry
     * @return its projection
     */
    public static InputProjection of(InputBinding binding) {
        return new InputProjection(
                binding.origin(),
                binding.location(),
                binding.name(),
                binding.requiredness(),
                binding.defaultValue(),
                description(binding.annotations()));
    }

    /**
     * Projects every entry of an inventory, in inventory order.
     *
     * @param bindings the inventory
     * @return the projections, in the same order
     */
    public static List<InputProjection> ofAll(List<InputBinding> bindings) {
        return bindings.stream().map(InputProjection::of).toList();
    }

    /**
     * Returns the inventory entries a document publishes as Parameter Objects, in published order:
     * the method parameters first, then the composite fields, each in inventory order. The body and
     * every form field (method parameter or composite field at the {@code FORM} location) are left
     * out, since they belong to the request body.
     *
     * @param inventory the projected inventory, in inventory order
     * @return the published parameters' projections, in published order
     */
    public static List<InputProjection> parameterOrder(List<InputProjection> inventory) {
        List<InputProjection> ordered = new ArrayList<>();
        for (Origin origin : List.of(Origin.PARAMETER, Origin.COMPOSITE_FIELD)) {
            for (InputProjection input : inventory) {
                if (input.origin() == origin && input.location() != ParamLocation.FORM) {
                    ordered.add(input);
                }
            }
        }
        return ordered;
    }

    /**
     * Returns the Parameter Object facts this entry must publish: its name, its location in lower
     * case as {@code in}, {@code required} {@code true} exactly when the entry is {@code REQUIRED}
     * and absent otherwise, and its description.
     *
     * @return the expected published parameter
     * @throws IllegalStateException for the body, which publishes no Parameter Object
     */
    public PublishedParameter expectedPublished() {
        if (location == null || name == null) {
            throw new IllegalStateException("the body publishes no Parameter Object");
        }
        return new PublishedParameter(
                name,
                location.name().toLowerCase(Locale.ROOT),
                requiredness == Requiredness.REQUIRED ? Boolean.TRUE : null,
                description);
    }

    /**
     * Returns the expected Parameter Objects of an inventory, in published order.
     *
     * @param inventory the projected inventory, in inventory order
     * @return the expected published parameters
     */
    public static List<PublishedParameter> expectedPublished(List<InputProjection> inventory) {
        return parameterOrder(inventory).stream()
                .map(input -> input.expectedPublished())
                .toList();
    }

    private static @Nullable String description(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Parameter parameter) {
                String description = parameter.description();
                return description == null || description.isBlank() ? null : description;
            }
        }
        return null;
    }

    /**
     * Checks the components that every entry has.
     */
    public InputProjection {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(requiredness, "requiredness");
    }
}
