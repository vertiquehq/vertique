// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.core.application.RestApplication;

/**
 * Declaring type for application {@code a}. Never implemented: the test builds this
 * application's mount by hand through the package-private {@code Factory.createApplicationMount},
 * never through generated composition (like the other declaring-type fixtures).
 */
@RestApplication(
        name = "a",
        path = "/api/a",
        resources = {EchoResource.class})
public interface ApplicationA {}
