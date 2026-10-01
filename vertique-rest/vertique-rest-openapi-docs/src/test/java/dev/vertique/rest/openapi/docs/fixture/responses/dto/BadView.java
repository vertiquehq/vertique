// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * An output view the {@code vertique-strict} profile's output generator refuses.
 *
 * <p>Its member {@code amount} is declared {@link BigDecimal} and carries
 * {@code @Schema(implementation = String.class)}. The {@code vertique-strict} profile declares a
 * JSON Schema type override for {@code java.math.BigDecimal} in both directions, and the
 * schema-implementation guard refuses a {@code @Schema} implementation redirect on a member whose
 * declared type graph carries an effective profile override: {@code
 * generateCanonical(BadView.class)} on that profile's output generator throws a {@code
 * JsonSchemaGenerationException}. The built-in {@code vertique} profile declares no such override
 * and generates the view.
 */
public class BadView {

    /** A decimal redirected to a string schema, which the override makes ambiguous. */
    @Schema(implementation = String.class)
    public BigDecimal amount;

    /** Creates an empty view. */
    public BadView() {}
}
