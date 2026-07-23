// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a {@link WorkflowContract} interface as a query operation.
 *
 * <p>Query methods must return exactly {@code Future<WorkflowView>} and take exactly one parameter of
 * type {@code WorkflowInstanceId}. V1 exposes a single untyped workflow-view query — no supertype or
 * wildcard is accepted; typed query projections are a later, explicit API (richer query routing lands in
 * cycle 5). Both the runtime {@code WorkflowProxyValidator} and the {@code vertique-codegen-workflow}
 * processor enforce this exact return type.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowQuery {

    /**
     * The query name; used for routing in future cycles.
     *
     * @return the query name
     */
    String value();
}
