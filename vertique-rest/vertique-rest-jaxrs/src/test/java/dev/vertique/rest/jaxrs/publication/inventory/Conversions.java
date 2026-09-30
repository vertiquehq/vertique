// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite bound with {@code @Valid} and a {@code @ConvertGroup}, whose
 * only field carries a {@code Default}-group {@code @NotNull}.
 */
public class Conversions {

    /** Query {@code mode}. */
    @QueryParam("mode")
    @NotNull
    public String mode;
}
