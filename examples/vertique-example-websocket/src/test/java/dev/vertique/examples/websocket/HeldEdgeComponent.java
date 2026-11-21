// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.websocket.chat.ChatModule;
import dev.vertique.examples.websocket.resource.ResourceModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.rest.websocket.dagger.WebSocketModule;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import jakarta.inject.Singleton;

/** The example's component plus a startup step the test holds inside the edge phase. */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            JwtAuthModule.class,
            WebSocketModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            SecurityAuthzModule.class,
            AppModule.class,
            ChatModule.class,
            ResourceModule.class,
            HoldEdgeModule.class
        })
interface HeldEdgeComponent extends VertiqueApplicationComponent {}
