// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The published cross-transport input-processing fixture corpus (R03, finding #425).
 *
 * <p>T015's TP-002 cited a "published INP-001 cross-transport fixture set" that did not exist; every
 * transport's parity claim rested on a fixture set it authored itself. This class is the corpus that
 * closes that gap: one raw input tree, shaped for {@link CrossTransportFixtureLevel1}, and its expected
 * processed result, computed by actually invoking {@link CrossTransportUppercaseSanitizer#transform}
 * rather than restated as an independent literal.
 *
 * <p>Published from {@code vertique-input-processing} as a test-jar (see that module's {@code pom.xml})
 * and consumed at test scope by {@code vertique-rest-jaxrs} and {@code vertique-mcp-server}, mirroring
 * the existing {@code vertique-config-core} / {@code vertique-config-aws-ssm} test-jar precedent. Both
 * consumers depend on this exact published {@code dev.vertique:vertique-input-processing:test-jar}
 * coordinate and reference this exact class — there is no second, independently authored copy of this
 * corpus anywhere in the repository. A corpus authored twice would prove nothing about cross-transport
 * parity; this one is authored once and read by both.
 */
public final class CrossTransportInputCorpus {

    private CrossTransportInputCorpus() {}

    /**
     * Returns a fresh raw input tree shaped for {@link CrossTransportFixtureLevel1}: a
     * {@code Map<String, Object>} keyed by the record's own Java property names — so
     * {@code InputFieldNameResolver.IDENTITY} projects correctly on both transports — whose leaves are
     * plain, unsanitized strings.
     *
     * @return a new raw input tree; a fresh instance on every call
     */
    public static Map<String, Object> rawInput() {
        Map<String, Object> level2 = new LinkedHashMap<>();
        level2.put("scalar", "bravo-scalar");
        level2.put("items", List.of("bravo-item-0", "bravo-item-1"));
        level2.put("entries", Map.of("bravo-key", "bravo-value"));

        Map<String, Object> level1 = new LinkedHashMap<>();
        level1.put("scalar", "alpha-scalar");
        level1.put("items", List.of("alpha-item-0", "alpha-item-1"));
        level1.put("entries", Map.of("alpha-key", "alpha-value"));
        level1.put("nested", level2);
        return level1;
    }

    /**
     * Returns the expected processed tree: {@link #rawInput()} with every leaf string run through
     * {@link CrossTransportUppercaseSanitizer#transform}, computed by actually calling that method —
     * not restated as a separate literal — so this can never silently drift from what the sanitizer
     * itself does.
     *
     * @return the expected processed tree; a fresh instance on every call
     */
    public static Map<String, Object> expectedProcessedInput() {
        return transform(rawInput());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> transform(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(key, transformValue(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object transformValue(Object value) {
        if (value instanceof String s) {
            return CrossTransportUppercaseSanitizer.transform(s);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CrossTransportInputCorpus::transformValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            return transform((Map<String, Object>) map);
        }
        return value;
    }
}
