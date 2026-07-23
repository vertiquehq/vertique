// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestAuthenticationEvidence}.
 *
 * <p>Verifies the public helper contract: append/get semantics, insertion-order preservation,
 * defensive immutability of the returned list, and null-argument rejection.
 *
 * <p>Tests use Mockito to stub {@link RoutingContext#get(String)} and {@link
 * RoutingContext#put(String, Object)} with an in-memory map, so they are independent of the Vert.x
 * runtime.
 */
class RestAuthenticationEvidenceTest {

    // --- Shared fixtures ---

    private static final AuthenticationEvidence EVIDENCE_1 = makeEvidence("cred-1");
    private static final AuthenticationEvidence EVIDENCE_2 = makeEvidence("cred-2");
    private static final AuthenticationEvidence EVIDENCE_3 = makeEvidence("cred-3");

    /**
     * Builds a minimal {@link AuthenticationEvidence} instance with a stable credential id.
     *
     * @param credentialId the credential identifier to use
     * @return a populated evidence instance
     */
    private static AuthenticationEvidence makeEvidence(String credentialId) {
        return new AuthenticationEvidence(
                DefaultAuthMethod.jwt(),
                Optional.of(credentialId),
                Instant.EPOCH,
                Optional.empty(),
                new CustomVerificationSource("test", Map.of()),
                Map.of());
    }

    // --- RoutingContext stub backed by a Map ---

    /**
     * Creates a Mockito {@link RoutingContext} stub whose {@code get}/{@code put} operations are
     * backed by the supplied backing map. This allows testing the storage helper without a live
     * Vert.x router.
     *
     * @param backingMap the map that holds routing-context data entries
     * @return the mocked routing context
     */
    private static RoutingContext stubContext(Map<String, Object> backingMap) {
        RoutingContext ctx = mock(RoutingContext.class);
        // get(String) → unchecked read from the backing map
        when(ctx.get(anyString())).thenAnswer(inv -> backingMap.get(inv.getArgument(0, String.class)));
        // put(String, Object) → write to backing map, return the ctx
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            backingMap.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    // --- Tests ---

    @Nested
    @DisplayName("append and get — basic contract")
    class AppendAndGet {

        private Map<String, Object> store;
        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            store = new HashMap<>();
            ctx = stubContext(store);
        }

        @Test
        @DisplayName("get on empty context returns empty list")
        void getOnEmptyContextReturnsEmptyList() {
            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("append then get returns the single appended entry")
        void appendThenGetReturnsSingleEntry() {
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);

            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertEquals(1, result.size());
            assertSame(EVIDENCE_1, result.get(0));
        }

        @Test
        @DisplayName("multiple appends preserve insertion order")
        void multipleAppendsPreserveOrder() {
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);
            RestAuthenticationEvidence.append(ctx, EVIDENCE_2);
            RestAuthenticationEvidence.append(ctx, EVIDENCE_3);

            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertEquals(3, result.size());
            assertSame(EVIDENCE_1, result.get(0));
            assertSame(EVIDENCE_2, result.get(1));
            assertSame(EVIDENCE_3, result.get(2));
        }

        @Test
        @DisplayName("append is additive — same entry appended twice appears twice")
        void appendIsCumulative() {
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);

            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertEquals(2, result.size());
            assertSame(EVIDENCE_1, result.get(0));
            assertSame(EVIDENCE_1, result.get(1));
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            ctx = stubContext(new HashMap<>());
        }

        @Test
        @DisplayName("returned list from get on empty context is immutable")
        void emptyListIsImmutable() {
            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertThrows(UnsupportedOperationException.class, () -> result.add(EVIDENCE_1));
        }

        @Test
        @DisplayName("returned list from get after append is immutable")
        void listAfterAppendIsImmutable() {
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);
            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertThrows(UnsupportedOperationException.class, () -> result.add(EVIDENCE_2));
        }

        @Test
        @DisplayName("returned list from get after append is immutable — remove also throws")
        void listAfterAppendIsImmutableOnRemove() {
            RestAuthenticationEvidence.append(ctx, EVIDENCE_1);
            List<AuthenticationEvidence> result = RestAuthenticationEvidence.get(ctx);
            assertThrows(UnsupportedOperationException.class, () -> result.remove(0));
        }
    }

    @Nested
    @DisplayName("null argument rejection")
    class NullArguments {

        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            ctx = stubContext(new HashMap<>());
        }

        @Test
        @DisplayName("append(null, evidence) throws NullPointerException with message 'ctx'")
        void appendNullContextThrowsNpe() {
            NullPointerException ex =
                    assertThrows(NullPointerException.class, () -> RestAuthenticationEvidence.append(null, EVIDENCE_1));
            assertEquals("ctx", ex.getMessage());
        }

        @Test
        @DisplayName("append(ctx, null) throws NullPointerException with message 'evidence'")
        void appendNullEvidenceThrowsNpe() {
            NullPointerException ex =
                    assertThrows(NullPointerException.class, () -> RestAuthenticationEvidence.append(ctx, null));
            assertEquals("evidence", ex.getMessage());
        }

        @Test
        @DisplayName("get(null) throws NullPointerException with message 'ctx'")
        void getNullContextThrowsNpe() {
            NullPointerException ex =
                    assertThrows(NullPointerException.class, () -> RestAuthenticationEvidence.get(null));
            assertEquals("ctx", ex.getMessage());
        }
    }
}
