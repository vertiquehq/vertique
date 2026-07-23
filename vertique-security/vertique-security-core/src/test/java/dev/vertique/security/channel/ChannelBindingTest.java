// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ChannelBinding} SPI contract.
 *
 * <p>Verifies: a recording fixture implementation satisfies the interface; {@code rebind()} records
 * the new context and returns a succeeded future; {@code close()} records the reason code and
 * returns a succeeded future; calling both in sequence produces the expected recorded state.
 */
class ChannelBindingTest {

    // --- fixture ---

    /** Minimal {@link SecurityContext} stub for rebind tests. */
    private static SecurityContext stubContext(String userId) {
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
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
        };
    }

    /**
     * Recording {@link ChannelBinding} that stores the most recent rebind context and close reason.
     */
    private static final class RecordingBinding implements ChannelBinding {

        SecurityContext lastBoundCtx;
        String lastReasonCode;
        boolean resourcesReleased;

        @Override
        public Future<Void> rebind(SecurityContext newCtx) {
            lastBoundCtx = newCtx;
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> close(String reasonCode) {
            lastReasonCode = reasonCode;
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> releaseResources() {
            resourcesReleased = true;
            return Future.succeededFuture();
        }
    }

    // --- rebind ---

    @Test
    @DisplayName("rebind() records the new SecurityContext and returns a succeeded future")
    void rebindRecordsContextAndSucceeds() {
        RecordingBinding binding = new RecordingBinding();
        SecurityContext ctx = stubContext("user-1");

        Future<Void> result = binding.rebind(ctx);

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals(ctx, binding.lastBoundCtx);
    }

    @Test
    @DisplayName("rebind() replaces a previously recorded context on second call")
    void rebindReplacesContext() {
        RecordingBinding binding = new RecordingBinding();
        SecurityContext first = stubContext("user-1");
        SecurityContext second = stubContext("user-2");

        binding.rebind(first);
        binding.rebind(second);

        assertEquals(second, binding.lastBoundCtx);
    }

    // --- close ---

    @Test
    @DisplayName("close() records the reason code and returns a succeeded future")
    void closeRecordsReasonCodeAndSucceeds() {
        RecordingBinding binding = new RecordingBinding();

        Future<Void> result = binding.close("IDENTITY_EXPIRED");

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals("IDENTITY_EXPIRED", binding.lastReasonCode);
    }

    @Test
    @DisplayName("close() with IDENTITY_REVOKED reason records correctly")
    void closeWithRevocationReason() {
        RecordingBinding binding = new RecordingBinding();

        binding.close("IDENTITY_REVOKED");

        assertEquals("IDENTITY_REVOKED", binding.lastReasonCode);
    }

    // --- combined sequence ---

    @Test
    @DisplayName("rebind then close — both operations record independently")
    void rebindThenCloseRecordIndependently() {
        RecordingBinding binding = new RecordingBinding();
        SecurityContext ctx = stubContext("user-3");

        binding.rebind(ctx);
        binding.close("CHANNEL_CLOSED_BY_SERVER");

        assertEquals(ctx, binding.lastBoundCtx);
        assertEquals("CHANNEL_CLOSED_BY_SERVER", binding.lastReasonCode);
    }

    @Test
    @DisplayName("initial state has null recorded fields before any call")
    void initialStateIsNull() {
        RecordingBinding binding = new RecordingBinding();

        assertNull(binding.lastBoundCtx);
        assertNull(binding.lastReasonCode);
    }
}
