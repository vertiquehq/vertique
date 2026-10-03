// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.completion;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.Collections;

/**
 * Dagger fixture module of the synthetic-operation completion proof: the stub {@code stub} scheme
 * authenticating from {@code X-Test-User}/{@code X-Test-Roles}, the {@link AuthEnforcementCapability}
 * marker, a recording contributor that keeps each operation's {@code context.operation()}, a
 * role-checking contributor (403 when the caller lacks a required role), recording
 * {@link RestRequestCompletedListener} and {@link HttpRequestCompletedListener} bindings, and the
 * {@link CompletionDocsMount}. Every collaborator {@code RestModule} requires but a real security
 * module would supply is stood in for here.
 */
@Module
final class CompletionFixtureModule {

    private CompletionFixtureModule() {}

    @Provides
    @Singleton
    static CompletionRecords completionRecords() {
        return new CompletionRecords();
    }

    /**
     * The unsecured {@link SecurityPolicyValidator} stand-in. {@code null} is a legal value: the
     * consuming constructor parameter is {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    @Provides
    static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    @Provides
    @IntoSet
    static SecuritySchemeHandler stubSchemeHandler() {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return StubAuthentication.SCHEME;
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                registry.authenticationHandler(ctx -> {
                    String user = ctx.request().getHeader(StubAuthentication.USER_HEADER);
                    if (user == null) {
                        ctx.fail(401);
                        return;
                    }
                    StubAuthentication.putRoles(
                            ctx,
                            StubAuthentication.parseRoles(ctx.request().getHeader(StubAuthentication.ROLES_HEADER)));
                    ctx.next();
                });
            }
        };
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor recordingContributor(CompletionRecords records) {
        return new RecordingContributor(records);
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor roleCheckingContributor() {
        return new RoleCheckingContributor();
    }

    @Provides
    @IntoSet
    static RestRequestCompletedListener restListener(CompletionRecords records) {
        return records::recordRest;
    }

    @Provides
    @IntoSet
    static HttpRequestCompletedListener httpListener(CompletionRecords records) {
        return records::recordHttp;
    }

    @Provides
    @IntoSet
    static RouterMount completionDocsMount(SyntheticOperationInstaller operations) {
        return new CompletionDocsMount(operations);
    }

    /**
     * Records each operation's {@link OperationRegistrationContext#operation()} at router build and
     * adds no handler, so its position in the chain is immaterial.
     */
    static final class RecordingContributor implements OperationHandlerContributor {

        private final CompletionRecords records;

        RecordingContributor(CompletionRecords records) {
            this.records = records;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            records.recordRegistered(context.operation());
        }

        @Override
        public String orderKey() {
            return "recording";
        }
    }

    /**
     * Fails the request with 403 when the effective policy requires roles the authenticated caller
     * does not hold; otherwise continues.
     */
    static final class RoleCheckingContributor implements OperationHandlerContributor {

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            SecurityPolicy policy = context.securityPolicy();
            context.route().addHandler(ctx -> {
                if (policy instanceof SecurityPolicy.Constrained constrained
                        && !constrained.requiredRoles().isEmpty()
                        && Collections.disjoint(constrained.requiredRoles(), StubAuthentication.roles(ctx))) {
                    ctx.fail(403);
                    return;
                }
                ctx.next();
            });
        }

        @Override
        public String orderKey() {
            return "roleChecking";
        }
    }
}
