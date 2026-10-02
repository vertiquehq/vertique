// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Application {@code child} at {@code /api/child}, documented publicly, whose declaring interface
 * carries its own {@code info} (title {@code Own}, version {@code 2}, a contact named {@code
 * OwnContact}) while extending {@link ParentInfoApi}, whose complete {@code info} must contribute
 * nothing. The annotation processor refuses this shape, so only a hand-written registration declares
 * it, and only in a view that never builds mounts.
 */
@OpenAPIDefinition(info = @Info(title = "Own", version = "2", contact = @Contact(name = "OwnContact")))
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = InfoRegistrations.CHILD_NAME,
        path = InfoRegistrations.CHILD_PATH,
        resources = InfoPingResource.class)
public interface OwnInfoApi extends ParentInfoApi {}
