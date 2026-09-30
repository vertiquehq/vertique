// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * INTERNAL: one input the binding owner flattened for an operation: a bound method parameter, the
 * request body, or a field of a {@code @BeanParam} or {@code @RequestParams} composite. Public only
 * for cross-module use by sibling framework modules; outside the maturity promise and not an
 * application contract.
 *
 * @param origin               where the input comes from
 * @param location             the input's location; {@code null} exactly for {@link Origin#BODY}
 * @param name                 the bound name; {@code null} exactly for {@link Origin#BODY}
 * @param type                 the input's generic type
 * @param defaultValue         the {@code @DefaultValue} the input binds when absent, or {@code null}
 *                             when it declares none
 * @param requiredness         whether the runtime rejects a missing value; always {@link
 *                             Requiredness#UNKNOWN} for {@link Origin#BODY}, since an absent body
 *                             binds as {@code null} and whether the request-validation gate rejects
 *                             it depends on the body schema, which this module does not evaluate
 * @param hidden               whether the input carries a hiding marker, belongs to a composite that
 *                             does, or is named by a hidden method-level parameter entry,
 *                             declared directly or through a composed annotation
 * @param schemaEnforced       {@code true} only when the request-validation gate was installed and
 *                             received a schema for this input
 * @param annotations          the annotations the input's requiredness and hidden flag were
 *                             judged on, without duplicates and in first-occurrence order; for a
 *                             {@link Origin#COMPOSITE_FIELD}, the field's own annotations followed
 *                             by those of the record component, its accessor, and its backing
 *                             field; method-level parameter entries are not included
 * @param methodParameterIndex the index of the method parameter the input binds through; for a
 *                             {@link Origin#COMPOSITE_FIELD}, the composite parameter's index
 * @param compositeType        the composite type declaring a {@link Origin#COMPOSITE_FIELD} input,
 *                             or {@code null} for any other origin
 */
public record InputBinding(
        Origin origin,
        @Nullable ParamLocation location,
        @Nullable String name,
        Type type,
        @Nullable String defaultValue,
        Requiredness requiredness,
        boolean hidden,
        boolean schemaEnforced,
        List<Annotation> annotations,
        @Nullable Integer methodParameterIndex,
        @Nullable Class<?> compositeType) {

    /**
     * Compact constructor storing an unmodifiable copy of {@code annotations}.
     */
    public InputBinding {
        annotations = List.copyOf(annotations);
    }

    /** Where an input comes from. */
    public enum Origin {
        /** A bound method parameter. */
        PARAMETER,
        /** The request body. */
        BODY,
        /** A field of a {@code @BeanParam} or {@code @RequestParams} composite. */
        COMPOSITE_FIELD
    }

    /** Whether the runtime rejects a missing value for an input. */
    public enum Requiredness {
        /** A missing value is certainly rejected. */
        REQUIRED,
        /** A missing value certainly binds without rejection. */
        NOT_REQUIRED,
        /** Neither outcome is certain at this layer. */
        UNKNOWN
    }
}
