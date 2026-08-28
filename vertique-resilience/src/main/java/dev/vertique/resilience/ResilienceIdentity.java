// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Package-private application identity bridge shared by root-package components. */
final class ResilienceIdentity {

    private static final byte VERSION = 0x01;
    private static final String OPERATION_KIND = "application.operation";
    private static final int MAX_UTF8_BYTES = 4_096;

    private ResilienceIdentity() {}

    static String applicationOperationKey(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        byte[] component = validate(operationName);
        MessageDigest digest = sha256();
        digest.update(VERSION);
        updateLength(digest, OPERATION_KIND.length());
        digest.update(OPERATION_KIND.getBytes(StandardCharsets.UTF_8));
        updateLength(digest, 1);
        updateLength(digest, component.length);
        digest.update(component);
        return "application:operation:" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] validate(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("operationName must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("operationName contains an unpaired UTF-16 surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("operationName contains an unpaired UTF-16 surrogate");
            }
        }
        if (utf8.length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("operationName exceeds the 4,096-byte UTF-8 limit");
        }
        return utf8;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
    }
}
