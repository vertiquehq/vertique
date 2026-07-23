// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityContextServiceDispatchDecoder}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A {@link SecurityContext} value decodes successfully (identity result).
 *   <li>A {@link SecurityContext} subtype value decodes successfully (preserving the concrete
 *       type reference).
 *   <li>A non-{@link SecurityContext} value yields an empty result with a non-empty warning list.
 *   <li>A {@code null} value yields an empty result with no warnings (context not propagated).
 * </ul>
 */
class SecurityContextServiceDispatchDecoderTest {

    private final SecurityContextServiceDispatchDecoder decoder = new SecurityContextServiceDispatchDecoder();

    private static final ServiceDispatchDecodeContext DECODE_CTX = new ServiceDispatchDecodeContext("service-dispatch");

    // --- type() ---

    @Test
    @DisplayName("type() returns SecurityContext.class")
    void typeReturnsSecurityContextClass() {
        assertEquals(SecurityContext.class, decoder.type());
    }

    // --- key() ---

    @Test
    @DisplayName("key() defaults to SecurityContext FQCN")
    void keyDefaultsToFqcn() {
        assertEquals(SecurityContext.class.getName(), decoder.key());
    }

    // --- successful decode: exact interface type ---

    @Test
    @DisplayName("SecurityContext value decodes to a present result")
    void securityContextValueDecodesSuccessfully() {
        SecurityContext sc = stubSecurityContext("user-1");
        ContextDecodeResult<SecurityContext> result = decoder.decode(sc, DECODE_CTX);

        assertTrue(result.value().isPresent(), "value should be present");
        assertSame(sc, result.value().get(), "decoded value must be the same instance");
        assertTrue(result.warnings().isEmpty(), "no warnings on success");
    }

    // --- successful decode: subtype ---

    @Test
    @DisplayName("SecurityContext subtype value decodes successfully (preserves concrete type)")
    void subtypeSecurityContextDecodesSuccessfully() {
        SubtypeSecurityContext subtype = new SubtypeSecurityContext("client-42");
        ContextDecodeResult<SecurityContext> result = decoder.decode(subtype, DECODE_CTX);

        assertTrue(result.value().isPresent(), "subtype value should be present");
        assertSame(subtype, result.value().get(), "decoded value must be the same subtype reference");
        assertInstanceOf(SubtypeSecurityContext.class, result.value().get(), "concrete type must be preserved");
        assertTrue(result.warnings().isEmpty(), "no warnings on success");
    }

    // --- null value: empty result, no warnings ---

    @Test
    @DisplayName("null value yields empty result with no warnings")
    void nullValueYieldsEmptyResultNoWarnings() {
        ContextDecodeResult<SecurityContext> result = decoder.decode(null, DECODE_CTX);

        assertFalse(result.value().isPresent(), "value should be empty for null input");
        assertTrue(result.warnings().isEmpty(), "no warnings when value is absent");
    }

    // --- wrong type: empty result with warning ---

    @Test
    @DisplayName("non-SecurityContext value yields empty result with a warning")
    void wrongTypeYieldsEmptyResultWithWarning() {
        ContextDecodeResult<SecurityContext> result = decoder.decode("not-a-security-context", DECODE_CTX);

        assertFalse(result.value().isPresent(), "value should be empty for wrong type");
        assertFalse(result.warnings().isEmpty(), "at least one warning should be present");
    }

    @Test
    @DisplayName("warning for wrong type names the unexpected class")
    void warningNamesUnexpectedClass() {
        ContextDecodeResult<SecurityContext> result = decoder.decode(Integer.valueOf(42), DECODE_CTX);

        assertFalse(result.warnings().isEmpty());
        ContextDecodeWarning warning = result.warnings().get(0);
        assertTrue(
                warning.reason().contains(Integer.class.getName()),
                "warning reason should name the unexpected class: " + warning.reason());
    }

    @Test
    @DisplayName("warning key matches the decoder key()")
    void warningKeyMatchesDecoderKey() {
        ContextDecodeResult<SecurityContext> result = decoder.decode("unexpected", DECODE_CTX);

        assertFalse(result.warnings().isEmpty());
        assertEquals(decoder.key(), result.warnings().get(0).key());
    }

    // --- Helpers ---

    /**
     * Creates a minimal stub {@link SecurityContext} for a USER actor with the given id.
     *
     * @param userId the user identifier
     * @return a minimal security context implementation
     */
    private static SecurityContext stubSecurityContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new MinimalSecurityContext(identity, auth);
    }

    /**
     * Minimal {@link SecurityContext} for encoder/decoder tests. Delegates to the typed identity
     * model without depending on the package-private {@code AuthenticatedSecurityContext}.
     *
     * @param identity       the security identity
     * @param authentication the authentication state
     */
    record MinimalSecurityContext(SecurityIdentity identity, AuthenticationState authentication)
            implements SecurityContext {

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }

    /**
     * A concrete subtype of {@link SecurityContext} used to verify that subtype instances
     * are accepted via {@code isInstance} filtering and returned unchanged.
     *
     * @param clientId the client identifier
     */
    record SubtypeSecurityContext(String clientId) implements SecurityContext {

        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, clientId, Map.of()));
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }
}
