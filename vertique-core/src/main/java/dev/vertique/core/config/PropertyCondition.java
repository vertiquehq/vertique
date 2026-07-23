// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Immutable value type representing a single property-matching condition evaluated against a
 * runtime {@link JsonObject} configuration tree.
 *
 * <p>Conditions are the runtime mirror of the compile-time
 * {@code @ConditionalOnProperty} annotation. Generated code in {@code vertique-codegen-services}
 * and {@code vertique-codegen-jaxrs} declares {@code static final PropertyCondition[]} arrays and calls
 * {@link #matchesAll} to decide whether to activate a service-contract implementation or a
 * JAX-RS resource binding. This class is therefore a <strong>public, stable API</strong> — its
 * fully-qualified name is referenced by generated sources in two codegen modules and must not
 * be renamed or moved without a corresponding codegen update.
 *
 * <p>Companion utility: {@link JsonConfigPaths} provides the underlying dotted-path resolution
 * used by {@link #matchesAll}.
 *
 * @param name           the dotted-path configuration property name (e.g. {@code "sandboxEnabled"}
 *                       or {@code "feature.payment.enabled"})
 * @param havingValue    the string value the property must equal for the condition to be
 *                       satisfied; comparison uses {@link String#valueOf(Object)} so boolean and
 *                       numeric scalars are matched by their string representation
 * @param matchIfMissing {@code true} if a missing property counts as a satisfied condition;
 *                       {@code false} (the default) causes a missing property to fail the
 *                       condition immediately
 */
public record PropertyCondition(String name, String havingValue, boolean matchIfMissing) {

    /**
     * Evaluates an AND of all supplied conditions against the given configuration object.
     *
     * <p>Evaluation rules (applied in array order):
     * <ol>
     *   <li>If {@code conditions} is {@code null} or empty, returns {@code true} immediately
     *       (vacuous truth — no conditions means "always active").
     *   <li>For each condition, {@link JsonConfigPaths#resolve} is called to look up the
     *       property named by {@link #name()}:
     *       <ul>
     *         <li>{@link JsonConfigPaths.LookupStatus#INVALID_SHAPE} — throws
     *             {@link ConfigurationException}; the dotted path crosses a non-object node
     *             (e.g. a scalar at an intermediate segment).
     *         <li>{@link JsonConfigPaths.LookupStatus#MISSING} — if {@link #matchIfMissing()}
     *             is {@code false}, returns {@code false} immediately. If {@code true},
     *             continues to the next condition (does NOT return {@code true} — the AND must
     *             still check remaining conditions).
     *         <li>{@link JsonConfigPaths.LookupStatus#PRESENT} — if the resolved value is a
     *             {@link JsonObject} or {@link JsonArray}, throws {@link ConfigurationException}
     *             (conditions must resolve to scalars). Otherwise compares
     *             {@link String#valueOf(Object)} of the value to {@link #havingValue()}; if they
     *             are not equal, returns {@code false}.
     *       </ul>
     *   <li>After all conditions pass without short-circuiting, returns {@code true}.
     * </ol>
     *
     * @param config     the root configuration object; may be {@code null} (treated as an empty
     *                   config — all paths will resolve to {@link JsonConfigPaths.LookupStatus#MISSING})
     * @param conditions the conditions to evaluate; may be {@code null} or empty (vacuously true)
     * @return {@code true} if all conditions are satisfied, {@code false} if any condition fails
     * @throws ConfigurationException if any condition's path has an invalid shape (a non-object
     *                                node at an intermediate segment) or resolves to a
     *                                {@link JsonObject} or {@link JsonArray} leaf
     */
    public static boolean matchesAll(JsonObject config, PropertyCondition[] conditions) {
        if (conditions == null || conditions.length == 0) {
            return true;
        }
        for (PropertyCondition condition : conditions) {
            JsonConfigPaths.LookupResult result = JsonConfigPaths.resolve(config, condition.name());
            switch (result.status()) {
                case INVALID_SHAPE ->
                    throw new ConfigurationException("Conditional property path '" + condition.name()
                            + "' crosses non-object segment '" + result.failingSegment() + "'");
                case MISSING -> {
                    if (!condition.matchIfMissing()) {
                        return false;
                    }
                    // matchIfMissing=true: this condition is satisfied, but continue to the
                    // next — AND semantics require all remaining conditions to be evaluated.
                }
                case PRESENT -> {
                    Object value = result.value();
                    if (value instanceof JsonObject || value instanceof JsonArray) {
                        throw new ConfigurationException(
                                "Conditional property '" + condition.name() + "' must resolve to a scalar");
                    }
                    if (!String.valueOf(value).equals(condition.havingValue())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
