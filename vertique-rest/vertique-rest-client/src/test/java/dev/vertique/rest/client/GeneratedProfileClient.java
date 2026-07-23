// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.json.JsonProfile;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;

/**
 * Fixture client carrying a {@code @JsonProfile("profile-a")} annotation whose generated
 * proxy stand-in ({@link GeneratedProfileClient_RestClientProxy}) is present on the test classpath.
 *
 * <p>Used by {@code RestClientMapperPrecedenceTest#generatedProxy_inheritsResolvedMapper} to prove
 * codegen parity: the same builder both resolves the annotation's profile mapper and selects the
 * generated static proxy over the JDK dynamic fallback.
 */
@JsonProfile("profile-a")
@RestClient(name = "generated-profile-client", value = "http://localhost:9999")
interface GeneratedProfileClient {

    /** Single method — only the signature shape matters for selection. */
    @GET
    Future<String> get();
}
