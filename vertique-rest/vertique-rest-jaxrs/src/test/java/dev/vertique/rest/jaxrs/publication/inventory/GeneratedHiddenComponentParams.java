// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite with one component marked {@code @Schema(hidden = true)}
 * and one marked {@code @Schema(hidden = false)}. It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 *
 * <p>The {@code secret} component declares an explicit accessor: component annotations are not
 * propagated to an explicitly declared accessor, so its query binding sits on the accessor and its
 * {@code @Schema(hidden = true)} stays on the backing field only.
 *
 * @param override query {@code override}, hidden
 * @param mode     query {@code mode}, explicitly not hidden
 * @param secret   query {@code secret}, bound through its explicit accessor, hidden on the component
 */
@RequestParams
public record GeneratedHiddenComponentParams(
        @QueryParam("override") @Schema(hidden = true) String override,
        @QueryParam("mode") @Schema(hidden = false) String mode,
        @Schema(hidden = true) String secret) {

    /**
     * The explicitly declared accessor of {@code secret}, carrying its query binding.
     *
     * @return the secret
     */
    @Override
    @QueryParam("secret")
    public String secret() {
        return secret;
    }
}
