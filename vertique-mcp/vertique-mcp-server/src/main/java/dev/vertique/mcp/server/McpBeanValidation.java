// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * The one shared Jakarta Bean Validation {@link Validator} for stage 4 of the fixed request-time input
 * pipeline (contract §4.7).
 *
 * <p>{@link Validation#buildDefaultValidatorFactory()} builds a full XML/provider-discovery bootstrap,
 * which is expensive to repeat; every {@code prepare()} that runs Bean Validation on a materialized
 * tool-argument carrier shares this one instance instead of building its own. The default validator
 * factory and its {@link Validator} are documented thread-safe and reusable across concurrent
 * validations, so one process-wide instance is correct, not merely convenient.
 *
 * <p>This type performs no traversal, resolution, or request-time processing of its own — it exists
 * solely to own the one shared {@link Validator} instance. Not part of the application-facing public
 * surface: package-private per the frozen artifact inventory, matching {@link McpSchemaRegistry} and
 * {@link McpToolRegistry}.
 */
final class McpBeanValidation {

    /** The one shared, thread-safe Bean Validation validator every {@code prepare()} stage 4 uses. */
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private McpBeanValidation() {}
}
