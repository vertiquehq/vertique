// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the fragments of fragment-only schema references.
 *
 * <p>A fragment is read by percent-decoding it as UTF-8 and then reading it as an RFC 6901 JSON
 * Pointer: the empty fragment is the root, and every other fragment must start with {@code /}, with
 * {@code ~1} and {@code ~0} unescaped to {@code /} and {@code ~}. A fragment is written from
 * reference tokens by escaping each token per RFC 6901 and percent-encoding, as UTF-8, every
 * character outside the RFC 3986 fragment set.
 */
final class SchemaFragments {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SchemaFragments() {}

    /**
     * Reads the fragment of a fragment-only reference as JSON Pointer reference tokens.
     *
     * @param reference the reference, starting with {@code #}
     * @return the unescaped tokens, empty for the root; {@code null} when the fragment is not a JSON
     *     Pointer (an anchor name, a malformed percent-encoding or escape)
     */
    static List<String> pointerTokens(String reference) {
        String fragment = percentDecode(reference.substring(1));
        if (fragment == null) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        if (fragment.isEmpty()) {
            return tokens;
        }
        if (fragment.charAt(0) != '/') {
            return null;
        }
        for (String escaped : fragment.substring(1).split("/", -1)) {
            String token = unescape(escaped);
            if (token == null) {
                return null;
            }
            tokens.add(token);
        }
        return tokens;
    }

    /**
     * Writes a fragment-only reference from reference tokens.
     *
     * @param tokens the unescaped tokens
     * @return {@code #} followed by the escaped, percent-encoded JSON Pointer
     */
    static String reference(List<String> tokens) {
        StringBuilder pointer = new StringBuilder();
        for (String token : tokens) {
            pointer.append('/').append(token.replace("~", "~0").replace("/", "~1"));
        }
        StringBuilder reference = new StringBuilder("#");
        for (byte b : pointer.toString().getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (inFragmentSet(c)) {
                reference.append((char) c);
            } else {
                reference.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return reference.toString();
    }

    /**
     * Tells whether an octet is a character the RFC 3986 fragment set allows unencoded: unreserved,
     * sub-delimiters, {@code :}, {@code @}, {@code /}, {@code ?}.
     */
    private static boolean inFragmentSet(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,;=:@/?".indexOf(c) >= 0;
    }

    /**
     * Unescapes one RFC 6901 reference token, {@code ~1} to {@code /} and {@code ~0} to {@code ~}.
     *
     * @param escaped the escaped reference token
     * @return the unescaped token, or {@code null} for a {@code ~} not followed by {@code 0} or
     *     {@code 1}
     */
    static String unescape(String escaped) {
        StringBuilder token = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '~') {
                token.append(c);
            } else if (i + 1 < escaped.length() && escaped.charAt(i + 1) == '0') {
                token.append('~');
                i++;
            } else if (i + 1 < escaped.length() && escaped.charAt(i + 1) == '1') {
                token.append('/');
                i++;
            } else {
                return null;
            }
        }
        return token.toString();
    }

    /** Percent-decodes a fragment as UTF-8; {@code null} for a malformed escape or byte sequence. */
    private static String percentDecode(String fragment) {
        if (fragment.indexOf('%') < 0) {
            return fragment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int start = 0;
        for (int i = 0; i < fragment.length(); i++) {
            if (fragment.charAt(i) != '%') {
                continue;
            }
            bytes.writeBytes(fragment.substring(start, i).getBytes(StandardCharsets.UTF_8));
            if (i + 2 >= fragment.length()) {
                return null;
            }
            int high = Character.digit(fragment.charAt(i + 1), 16);
            int low = Character.digit(fragment.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes.write((high << 4) | low);
            i += 2;
            start = i + 1;
        }
        bytes.writeBytes(fragment.substring(start).getBytes(StandardCharsets.UTF_8));
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
