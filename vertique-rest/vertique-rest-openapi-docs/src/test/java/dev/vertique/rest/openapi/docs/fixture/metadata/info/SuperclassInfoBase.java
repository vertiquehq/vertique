// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * A superclass carrying a valid {@code info} whose title is the marker {@code SuperZx}. It declares no
 * application; {@link SubclassInfoApplication} extends it. The annotation type is inherited, so
 * reflection reports it on the subclass too, yet only a declaring type's own annotation counts: the
 * title may reach no document and no message.
 */
@OpenAPIDefinition(info = @Info(title = "SuperZx", version = "1"))
public abstract class SuperclassInfoBase {

    /** For the subclass. */
    protected SuperclassInfoBase() {}
}
