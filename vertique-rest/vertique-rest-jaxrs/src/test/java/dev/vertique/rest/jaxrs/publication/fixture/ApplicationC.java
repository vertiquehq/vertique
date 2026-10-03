// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.core.application.RestApplication;

/**
 * T006 TP-005's declaring type for application {@code c}. Never implemented: the test builds this
 * application's mount by hand through the package-private {@code Factory.createApplicationMount},
 * never through generated composition (mirrors T023's ported fixtures).
 */
@RestApplication(
        name = "c",
        path = "/api/c",
        resources = {EchoResource.class})
public interface ApplicationC {}
