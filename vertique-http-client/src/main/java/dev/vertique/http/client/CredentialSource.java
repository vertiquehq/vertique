// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import java.net.URI;
import java.util.Map;

/**
 * Supplies the credential headers of one exchange hop. The client calls it once for every hop to the
 * original origin, after the request is prepared, and replaces the request's header of each returned
 * name with the returned value.
 *
 * <p>The client never calls it for a hop to another origin or after the exchange left the original
 * origin, and not for a request with a server address, a virtual host (including a caller-set {@code
 * Host} header) or a request-level proxy. A failed or null result, a header name that is not an HTTP
 * token or is owned by the transport ({@code Host}, {@code Content-Length}, {@code Transfer-Encoding},
 * {@code Connection}, {@code Upgrade}, {@code TE}), and a value with a control character or with leading
 * or trailing whitespace, fail the exchange with {@code HttpCredentialException} and send nothing. The
 * client never renders returned values into a message, log or {@code toString}.
 */
@FunctionalInterface
public interface CredentialSource {

    /**
     * Resolves the credential headers for one hop.
     *
     * @param target the absolute URI of the hop the headers are for
     * @return the headers to set, by name, completed with a failure when none can be produced
     */
    Future<Map<String, String>> resolve(URI target);
}
