// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} record composite of {@link PrimitiveKindsResource#listPrimitiveKinds}. An
 * absent primitive member is left out of the bound map, so the mapper sets {@code 0} and a
 * {@code @NotNull} on it never fires. The {@code region} component declares an explicit accessor,
 * so its {@code @NotNull} is not propagated to that accessor and stays on the backing field only.
 * It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 *
 * @param page   query {@code page}, a primitive {@code int} with only {@code @NotNull}
 * @param offset query {@code offset}, a primitive {@code int} with {@code @NotNull} and {@code @Min(1)}
 * @param region query {@code region}, bound through its explicit accessor, {@code @NotNull} on the
 *               component
 */
public record PrimitiveLimits(
        @QueryParam("page") @NotNull int page,
        @QueryParam("offset") @NotNull @Min(1) int offset,
        @NotNull String region) {

    /**
     * The explicitly declared accessor of {@code region}, carrying its query binding.
     *
     * @return the region
     */
    @Override
    @QueryParam("region")
    public String region() {
        return region;
    }
}
