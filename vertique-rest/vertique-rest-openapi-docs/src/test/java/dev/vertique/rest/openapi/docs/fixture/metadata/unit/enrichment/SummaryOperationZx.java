// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Operation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The control of {@link InternalOperationZx}: a composed method annotation whose type carries
 * {@code @Operation(summary = "S")}, which does not hide.
 */
@Operation(summary = "S")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SummaryOperationZx {}
