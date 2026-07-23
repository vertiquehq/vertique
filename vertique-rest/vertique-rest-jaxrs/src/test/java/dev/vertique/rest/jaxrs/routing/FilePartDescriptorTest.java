// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that file-part descriptors are valid and immutable by construction. */
class FilePartDescriptorTest {

    @Test
    @DisplayName("The constructor enforces all file-part descriptor invariants")
    void constructorInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new FilePartDescriptor("", List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> new FilePartDescriptor("   ", List.of(), -1));
        assertThrows(NullPointerException.class, () -> new FilePartDescriptor("avatar", null, -1));
        assertThrows(
                NullPointerException.class,
                () -> new FilePartDescriptor("avatar", Arrays.asList("image/png", null), -1));

        for (String malformed : List.of(
                "",
                "image",
                "/png",
                "image/",
                "image/png/extra",
                "*/*",
                "*/png",
                "image/p*ng",
                "image/png; q=1",
                "image/\tpng")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new FilePartDescriptor("avatar", List.of(malformed), -1),
                    () -> "Expected malformed media type to be rejected: " + malformed);
        }

        assertThrows(IllegalArgumentException.class, () -> new FilePartDescriptor("avatar", List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new FilePartDescriptor("avatar", List.of(), -2));

        FilePartDescriptor canonical = new FilePartDescriptor("avatar", List.of("Image/PNG", "APPLICATION/*"), 4096);
        assertEquals(List.of("image/png", "application/*"), canonical.allowedTypes());

        List<String> mutableAllowedTypes = new ArrayList<>(List.of("image/png"));
        FilePartDescriptor copied = new FilePartDescriptor("avatar", mutableAllowedTypes, -1);
        mutableAllowedTypes.set(0, "application/pdf");
        assertEquals(List.of("image/png"), copied.allowedTypes());
        assertThrows(
                UnsupportedOperationException.class, () -> copied.allowedTypes().add("application/pdf"));
    }
}
