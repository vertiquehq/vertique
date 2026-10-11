// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.gip.chain;

import dev.vertique.codegen.jaxrs.gip.relay.Relay;
import jakarta.ws.rs.Path;

/**
 * Back in the package of {@link RelayBase}, but through {@link Relay} of another package: its
 * {@code get(String)} does not override {@link RelayBase#get(Object)} and is no route.
 */
@Path("/relay")
public class RelayLeaf extends Relay<String> {

    String get(String id) {
        return "relay " + id;
    }
}
