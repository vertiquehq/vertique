// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Package-private codec for deriving bounded, opaque adapter operation and state keys. */
final class AdapterOperationIdentityCodec {

    private static final byte ENCODING_VERSION = 0x01;

    private AdapterOperationIdentityCodec() {}

    static String derive(AdapterOperationIdentity identity) {
        MessageDigest digest = newSha256();
        digest.update(ENCODING_VERSION);

        byte[] kind = identity.kind().getBytes(StandardCharsets.UTF_8);
        updateLength(digest, kind.length);
        digest.update(kind);

        updateLength(digest, identity.components().size());
        for (String component : identity.components()) {
            byte[] encodedComponent = component.getBytes(StandardCharsets.UTF_8);
            updateLength(digest, encodedComponent.length);
            digest.update(encodedComponent);
        }

        return identity.kind().replace('.', ':') + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void updateLength(MessageDigest digest, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("encoded length does not fit uint32");
        }
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
    }
}
