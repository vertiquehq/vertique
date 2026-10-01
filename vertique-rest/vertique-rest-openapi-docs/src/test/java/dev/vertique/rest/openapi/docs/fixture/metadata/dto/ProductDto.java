// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A request body whose JSON names differ from its Java member names: the creator parameter {@code
 * displayName} binds as {@code display_name}, the setter {@code setSku} as {@code sku_code}. Each
 * renamed member carries a constraint and {@code @Schema} documentation, so the generated schema
 * holds {@code display_name} with {@code maxLength} 40, {@code description} and {@code title}, and
 * {@code sku_code} with its {@code pattern} and {@code description}.
 *
 * <p>The shape is deliberate. The fields are private and there are no getters: an unannotated getter
 * such as {@code getDisplayName()} would add a stray {@code displayName} property. The pattern
 * constraint sits on the setter method itself; with no Bean Validation {@code Validator} passed to
 * the generator that is how the generator reads it, so a composition using this body must bind no
 * {@code jakarta.validation.Validator}.
 */
public final class ProductDto {

    private final String displayName;

    private String sku;

    /**
     * Creates a product from its JSON display name.
     *
     * @param displayName the display name, bound from {@code display_name}
     */
    @JsonCreator
    public ProductDto(
            @JsonProperty("display_name")
                    @Size(max = 40)
                    @Schema(description = "Shown to buyers", title = "Display name")
                    String displayName) {
        this.displayName = displayName;
    }

    /**
     * Sets the stock code, bound from {@code sku_code}.
     *
     * @param sku the stock code
     */
    @JsonProperty("sku_code")
    @Pattern(regexp = "^[A-Z]{3}-[0-9]{4}$")
    @Schema(description = "Stock code")
    public void setSku(String sku) {
        this.sku = sku;
    }
}
