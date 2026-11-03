// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;

/** A canonical text of a JSON tree, with sorted keys, so that equal trees give equal hashes. */
public final class Canonical {

    private Canonical() {}

    /**
     * Renders a tree canonically.
     *
     * @param value a {@link JsonObject}, {@link JsonArray} or scalar
     * @return the text
     */
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /**
     * Hashes a tree without some top-level keys.
     *
     * @param object the tree
     * @param without the top-level keys to leave out
     * @return the SHA-256 of the canonical text, in hexadecimal
     */
    public static String sha256(JsonObject object, Set<String> without) {
        JsonObject copy = object.copy();
        without.forEach(copy::remove);
        return sha256(encode(copy).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes bytes.
     *
     * @param bytes the bytes
     * @return the SHA-256 in hexadecimal
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String text) {
        String encoded = new JsonArray().add(text).encode();
        return encoded.substring(1, encoded.length() - 1);
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out) {
        if (value instanceof JsonObject object) {
            out.append('{');
            boolean first = true;
            for (var entry : new TreeMap<>(object.getMap()).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(entry.getKey()));
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof JsonArray array) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(array.getValue(i), out);
            }
            out.append(']');
        } else if (value instanceof java.util.Map<?, ?> map) {
            write(new JsonObject((java.util.Map<String, Object>) map), out);
        } else if (value instanceof java.util.List<?> list) {
            write(new JsonArray((java.util.List<Object>) list), out);
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
        } else if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean) {
            out.append(value);
        } else {
            out.append(quote(value.toString()));
        }
    }
}
