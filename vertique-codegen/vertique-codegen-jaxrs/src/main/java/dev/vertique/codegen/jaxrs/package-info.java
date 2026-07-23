// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * CG-009 — Annotation processor that validates JAX-RS resource classes at compile time,
 * providing faster developer feedback and deterministic CI catch for annotation-visible defects.
 *
 * <p>Anchored on {@code @Path}-annotated concrete classes. For each resource class, the
 * processor validates:
 * <ul>
 *   <li><strong>Security-annotation conflicts</strong> — mirrors
 *       {@link dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver}: {@code @DenyAll}
 *       combined with {@code @PermitAll}/{@code @RolesAllowed}/{@code @Authorized} at the same
 *       declaration level, {@code @PermitAll} combined with {@code @RolesAllowed}/{@code @Authorized},
 *       and empty {@code @RolesAllowed({})}. Tier-A acceptance parity: same wording as the runtime
 *       {@code SecurityPolicyViolationException} channel.</li>
 *   <li><strong>Multiple HTTP verbs on one method</strong> — Tier-B build-time-only guardrail:
 *       more than one of {@code @GET}/{@code @POST}/{@code @PUT}/{@code @DELETE}/{@code @PATCH}/
 *       {@code @HEAD}/{@code @OPTIONS} on a single method is always a typo. Runtime picks the
 *       first; APT errors.</li>
 *   <li><strong>Path-placeholder / {@code @PathParam} alignment</strong> — pre-empted defect:
 *       runtime never validates this at startup; a mismatch produces a silent {@code null} at
 *       dispatch. APT checks that every {@code {name}} placeholder in the combined class+method
 *       path has a matching {@code @PathParam("name")}, and vice versa. Composite parameter types
 *       ({@code @BeanParam}, {@code @RequestParams}-annotated types) are also scanned for
 *       contributed path params.</li>
 *   <li><strong>Body/form exclusivity</strong> — mirrors {@code RouteValidator.validateMethodParams}:
 *       more than one unannotated body parameter, or a mix of form/file-upload parameters and a
 *       body parameter. Tier-A acceptance parity.</li>
 * </ul>
 *
 * <p>This module generates no source files — it is a pure validator. It returns {@code false}
 * from {@link javax.annotation.processing.Processor#process} so other processors continue to
 * see the annotated elements.
 *
 * <p>Shared helpers used by both this module and {@code vertique-codegen-rest-client}:
 * <ul>
 *   <li>{@link dev.vertique.codegen.PathPlaceholders} — placeholder extraction with
 *       {@code :regex} suffix stripping</li>
 *   <li>{@link dev.vertique.codegen.JaxRsBeanScanner} — {@code @PathParam} name extraction
 *       from composite parameter types (records and classes)</li>
 * </ul>
 */
package dev.vertique.codegen.jaxrs;
