// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.security.authz.ReconstructedAuthorityMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReconstructionMarker} — the typed, unforgeable verified-reconstruction
 * signal a {@link SecurityContext} carries via {@link SecurityContext#reconstruction()}.
 */
class ReconstructionMarkerTest {

    @Test
    @DisplayName("a null mode is rejected with NullPointerException")
    void requiresMode() {
        assertThrows(NullPointerException.class, () -> new ReconstructionMarker(null));
    }

    @Test
    @DisplayName("carries the given authority mode as-is")
    void carriesMode() {
        ReconstructionMarker marker = new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED);
        assertEquals(ReconstructedAuthorityMode.CAPTURED, marker.mode());
    }

    @Test
    @DisplayName("LIVE_RESOLVED is rejected — it is an evaluation outcome, never a reconstruction disposition")
    void rejectsLiveResolvedMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReconstructionMarker(ReconstructedAuthorityMode.LIVE_RESOLVED));
    }
}
