// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.security.scheme.Http;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves T009's TP-005: {@link JwtBearerSecuritySchemeHandler#openApiDescription()} always
 * describes an HTTP bearer scheme with {@code bearerFormat} {@code "JWT"}, whatever the handler's
 * configured scheme name, and never leaks its configured issuer or audience through the
 * description's {@code toString()}.
 */
class JwtBearerSchemeDescriptionTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String AUDIENCE = "https://api.example.com";

    private static ContextHolder holderWith(CorrelationContext correlation) {
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        return holder;
    }

    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    private static JwtBearerSecuritySchemeHandler handlerWithSchemeName(String schemeName) {
        ContextHolder holder = holderWith(stubCorrelation());
        DefaultCredentialRejectionReporter reporter =
                new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of()));
        JwtValidationConfig config = JwtValidationConfig.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .build();

        return new JwtBearerSecuritySchemeHandler(schemeName, mock(JWTAuth.class), config, reporter);
    }

    @ParameterizedTest(name = "schemeName={0}")
    @ValueSource(strings = {"bearerAuth", "partnerJwt"})
    @DisplayName("openApiDescription() returns HTTP bearer with format JWT, whatever the scheme name, and leaks"
            + " neither the issuer nor the audience")
    void describesHttpBearerWithJwtFormat(String schemeName) {
        JwtBearerSecuritySchemeHandler handler = handlerWithSchemeName(schemeName);

        Optional<SecuritySchemeDescription> description = handler.openApiDescription();

        assertEquals(Optional.of(Http.bearer("JWT")), description);

        String rendered = description.orElseThrow().toString();
        assertFalse(rendered.contains(ISSUER), "description toString() must not leak the configured issuer");
        assertFalse(rendered.contains(AUDIENCE), "description toString() must not leak the configured audience");
        assertTrue(rendered.contains("JWT"), "description toString() should still print the bearer format");
    }
}
