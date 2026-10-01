// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.vault;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@value #ROUTE} of {@link VaultApi}: {@code GET /secrets} (operation {@value
 * #READ_VAULT}) requires the scheme {@value UndescribedVaultHandler#VAULT_AUTH} and answers a fixed
 * plain-text body once authenticated, which no test request is.
 */
@Path(VaultResource.ROUTE)
public class VaultResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/secrets";

    /** The operation id of {@link #readVault}. */
    public static final String READ_VAULT = "readVault";

    /** Creates the resource. */
    public VaultResource() {}

    /**
     * Handles {@code GET /secrets}.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = UndescribedVaultHandler.VAULT_AUTH)
    public String readVault() {
        return "sealed";
    }
}
