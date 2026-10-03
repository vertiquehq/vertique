// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.core.application.RestApplication;

/**
 * An undocumented application whose name is the longest the application-name grammar accepts:
 * {@code a} followed by 63 {@code b}s, 64 characters. It lists {@link LongNameResource} and carries
 * no {@code @ApiDocs}.
 */
@RestApplication(name = LongNameApi.NAME, path = LongNameApi.PATH, resources = LongNameResource.class)
public interface LongNameApi {

    /** The application's name: {@code a} followed by 63 {@code b}s. */
    String NAME = "abbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    /** The application's path. */
    String PATH = "/api/long-name";
}
