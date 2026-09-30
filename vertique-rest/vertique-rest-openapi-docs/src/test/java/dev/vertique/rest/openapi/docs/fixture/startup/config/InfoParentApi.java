// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * A superinterface carrying a complete {@code info} (title {@code Inherited}, version {@code 3}).
 * It declares no application; {@link InheritedInfoApi} extends it. Only a declaring interface's own
 * annotation counts, so this {@code info} never reaches a document.
 */
@OpenAPIDefinition(info = @Info(title = "Inherited", version = "3", description = "From the superinterface"))
public interface InfoParentApi {}
