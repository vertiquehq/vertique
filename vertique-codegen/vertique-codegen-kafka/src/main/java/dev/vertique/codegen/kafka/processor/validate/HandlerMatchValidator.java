// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.scan.RouteModel;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates the match-rule mutual-exclusion constraint on each {@code @KafkaHandler} method
 * within a Model 3 router listener (FR-CG006-005).
 *
 * <p>Each {@code @KafkaHandler} method must specify <em>exactly one</em> of:
 * <ul>
 *   <li>{@code matchHeader} non-empty</li>
 *   <li>{@code matchProperty} non-empty</li>
 *   <li>{@code defaultHandler = true}</li>
 * </ul>
 *
 * <p>Additionally, at most one {@code defaultHandler = true} is permitted per router. A second
 * {@code defaultHandler} also violates the exclusive-one rule and is reported via the same
 * {@link Diagnostics#kafkaHandlerMatchRule} formatter.
 *
 * <p>This validator also rejects <strong>duplicate route selectors</strong>: two routes with the
 * same {@code matchHeader}+{@code matchValue}, or the same {@code matchProperty}+{@code matchValue}.
 * {@code KafkaRecordDispatcher.resolveRoute} matches header routes first (in declaration order), then
 * property routes, then the default, so an identical selector makes the second route unreachable within
 * its pass. Selectors are namespaced by kind, so a header and a property sharing a name/value are not
 * duplicates (both can be meaningful). Note this is <em>selector</em> uniqueness, not payload-type
 * uniqueness: two routes that share a payload schema but match on different selectors are valid and
 * accepted.
 *
 * <p>This validator is a no-op for Model 4 binding listeners (they have no routes).
 */
public final class HandlerMatchValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerMatchValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates every route in the listener model and returns {@code true} only when all routes
     * satisfy the match-rule constraint.
     *
     * @param model the scanned listener model to validate; must not be {@code null}
     * @return {@code true} when all routes are valid; {@code false} when at least one diagnostic
     *         was emitted
     */
    public boolean validate(ListenerModel model) {
        if (model.kind() != ListenerModel.Kind.ROUTER) {
            return true; // Model 4 direct handlers have no @KafkaHandler routes to validate.
        }

        String listenerName = model.originType().getSimpleName().toString();
        boolean allOk = true;
        int defaultHandlerCount = 0;
        // Track header/property selectors to reject exact duplicates (same kind+name+value). The key is a
        // structured SelectorKey, not a concatenated string, so a name or value containing the delimiter
        // characters cannot forge a false collision. Kind namespacing means a header and a property sharing
        // a name/value are not duplicates — header routes are matched before property routes (each pass in
        // declaration order, per KafkaRecordDispatcher.resolveRoute), so an exact duplicate within a pass is
        // the dead route, while a cross-kind pair can both be reachable.
        Set<SelectorKey> selectorsSeen = new HashSet<>();

        for (RouteModel route : model.routes()) {
            boolean hasMatchHeader =
                    route.matchHeader() != null && !route.matchHeader().isBlank();
            boolean hasMatchProperty =
                    route.matchProperty() != null && !route.matchProperty().isBlank();
            boolean isDefault = route.defaultHandler();

            int matchCount = (hasMatchHeader ? 1 : 0) + (hasMatchProperty ? 1 : 0) + (isDefault ? 1 : 0);

            if (matchCount != 1) {
                ctx.diagnostics()
                        .error(
                                route.method(),
                                Diagnostics.kafkaHandlerMatchRule(
                                        listenerName,
                                        route.method().getSimpleName().toString()));
                allOk = false;
                continue;
            }

            if (isDefault) {
                defaultHandlerCount++;
                if (defaultHandlerCount > 1) {
                    // Second defaultHandler also violates the exclusive-one rule.
                    ctx.diagnostics()
                            .error(
                                    route.method(),
                                    Diagnostics.kafkaHandlerMatchRule(
                                            listenerName,
                                            route.method().getSimpleName().toString()));
                    allOk = false;
                }
            } else if (hasMatchHeader) {
                if (!selectorsSeen.add(new SelectorKey("header", route.matchHeader(), route.matchValue()))) {
                    ctx.diagnostics()
                            .error(
                                    route.method(),
                                    Diagnostics.kafkaDuplicateRouteSelector(
                                            listenerName, "header", route.matchHeader(), route.matchValue()));
                    allOk = false;
                }
            } else if (!selectorsSeen.add(new SelectorKey("property", route.matchProperty(), route.matchValue()))) {
                ctx.diagnostics()
                        .error(
                                route.method(),
                                Diagnostics.kafkaDuplicateRouteSelector(
                                        listenerName, "property", route.matchProperty(), route.matchValue()));
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * Structured route-selector identity used for duplicate detection: the selector kind
     * ({@code "header"} / {@code "property"}), the header/property name, and the match value. Using a
     * record (field-wise equality) rather than a delimiter-joined string avoids a false collision when a
     * name or value itself contains the delimiter characters.
     *
     * @param kind  the selector kind, {@code "header"} or {@code "property"}
     * @param name  the header or property name
     * @param value the match value
     */
    private record SelectorKey(String kind, String name, String value) {}
}
