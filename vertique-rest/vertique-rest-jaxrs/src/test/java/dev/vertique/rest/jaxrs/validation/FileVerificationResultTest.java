// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the immutable result contract exposed to file-content verifier extensions. */
class FileVerificationResultTest {

    @Test
    @DisplayName("Rejected results require non-blank detail and type values")
    void rejectedRequiresNonBlankDetailAndType() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected(null, "rejected", null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected("", "rejected", null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected("   ", "rejected", null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected("not allowed", null, null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected("not allowed", "", null)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new FileVerificationResult.Rejected("not allowed", "   ", null)));
    }

    @Test
    @DisplayName("Rejected results defensively copy verifier-supplied arguments")
    void rejectedArgsDefensivelyCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("expected", "image/png");

        FileVerificationResult.Rejected rejected =
                new FileVerificationResult.Rejected("signature mismatch", "fileSignatureMismatch", source);
        source.put("expected", "application/pdf");

        assertEquals(Map.of("expected", "image/png"), rejected.args());
        assertThrows(UnsupportedOperationException.class, () -> rejected.args().put("extra", true));
    }

    @Test
    @DisplayName("accepted() returns the canonical accepted result instance")
    void acceptedFactoryReturnsCanonicalInstance() {
        FileVerificationResult first = FileVerificationResult.accepted();
        FileVerificationResult second = FileVerificationResult.accepted();

        assertInstanceOf(FileVerificationResult.Accepted.class, first);
        assertSame(first, second);
    }
}
