// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * Application {@code child} at {@code /api/child}, documented publicly. It carries no {@code
 * OpenAPIDefinition} itself but extends {@link ParentInfoApi}, which does. The annotation processor
 * refuses this shape, so only a hand-written registration declares it, and only in a view that never
 * builds mounts.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface ChildInfoApi extends ParentInfoApi {}
