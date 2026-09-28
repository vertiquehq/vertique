// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.JsonFormatValidator;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import java.util.Set;

/**
 * The {@code web-validation} gate's pattern-input guard: it bounds the input of pattern evaluation and of
 * the three regex-backed formats not measured as linear-time ({@code idn-hostname}, {@code idn-email},
 * and {@code regex}), per string and per request.
 *
 * <p>The guard compiles a private, rewritten copy of each body and parameter schema. In the copy, every
 * node with a string {@code pattern} and every node whose {@code format} is a bounded format gains the
 * bound entry {@code {"format": "x-vertique-pattern-input-bound"}}, one per position, appended to its
 * {@code allOf}; every node with a non-empty {@code patternProperties} then gains {@code {"propertyNames":
 * <the bound entry>}}. The guard is the copy's {@link JsonFormatValidator}: at the bound entry it rejects a
 * string longer than the per-string limit and adds every other string's length to the request's running
 * total, rejecting once the total exceeds the per-request limit; every other format is delegated to
 * {@link JsonFormatValidator#DEFAULT_VALIDATOR}. The running total exists only inside a window the gate
 * opens around a request's validation.
 *
 * <p>The ordering rests on vertx-json-schema behaviors: a node's {@code allOf} is evaluated before the
 * node's own {@code pattern}, its key regexes, and the engine's own check for its {@code format}; the
 * format validator is called for every {@code format} assertion, including a name the engine does not
 * know, which passes the engine's built-in check; and an exception the format validator throws escapes
 * {@link Validator#validate(Object)} unwrapped. Entries are appended, never prepended, so every existing
 * {@code allOf} index and every reported keyword location stays valid against the original schema.
 */
final class PatternInputGuard implements JsonFormatValidator {

    /** The format name of the bound entry the rewrite appends. */
    static final String BOUND_FORMAT = "x-vertique-pattern-input-bound";

    /**
     * The formats bounded and counted at a bound entry of their own: the regex-backed checks not measured
     * as linear-time. Every other format is neither bounded nor counted.
     */
    private static final Set<String> BOUNDED_FORMATS = Set.of("idn-hostname", "idn-email", "regex");

    /** The JSON Schema format keyword. */
    private static final String FORMAT_KEYWORD = "format";

    /** The applicator the bound entries are appended to. */
    private static final String ALL_OF_KEYWORD = "allOf";

    /** The applicator that carries the bound entry to every key of an object. */
    private static final String PROPERTY_NAMES_KEYWORD = "propertyNames";

    /** The per-string limit, in UTF-16 code units. */
    private final int maxChars;

    /** The per-request limit, in UTF-16 code units. */
    private final int maxTotalChars;

    /**
     * The calling thread's running total for the request being validated, present only inside a window.
     * A one-element array so the bound entry adds to it without re-boxing.
     */
    private final ThreadLocal<long[]> tally = new ThreadLocal<>();

    /**
     * Creates a guard with the configured limits.
     *
     * @param maxChars      the per-string limit ({@code jaxrs.validationPatternMaxChars})
     * @param maxTotalChars the per-request limit ({@code jaxrs.validationPatternMaxTotalChars})
     */
    PatternInputGuard(int maxChars, int maxTotalChars) {
        this.maxChars = maxChars;
        this.maxTotalChars = maxTotalChars;
    }

    /**
     * Returns a rewritten deep copy of {@code schema}: the bound entry appended to the {@code allOf} of every
     * pattern position and every bounded-format node, one per position, then the {@code propertyNames} entry
     * at every non-empty {@code patternProperties}. {@code schema} itself is never modified, and a schema with
     * no such position yields a copy equal to it.
     *
     * <p>The walk follows the gate's regex precompilation: it never enters the value of a literal keyword
     * ({@code const}, {@code enum}, {@code default}, {@code examples}, {@code example}), it treats each member
     * of {@code properties}, {@code patternProperties}, {@code $defs}, and {@code dependentSchemas} as a
     * schema whatever its name, never the container itself, and it reads every other member name as a
     * keyword. It therefore reaches every schema position of a schema whose named members sit in those four
     * containers, as a generated schema's do. A member of the draft-7 container {@code dependencies} or
     * {@code definitions} that is named like a literal keyword or like one of the four containers is not
     * walked as a schema, so a pattern or bounded format in it can run on unbounded input, and a member
     * named {@code patternProperties} makes the rewrite append an {@code allOf} member to the container
     * itself.
     *
     * @param schema the schema, which stays untouched
     * @return the rewritten copy, always a new instance
     */
    static JsonObject rewrite(JsonObject schema) {
        JsonObject copy = schema.copy();
        rewriteNode(copy, false);
        return copy;
    }

    /**
     * Walks one value of the copy, rewriting every schema node beneath it after its members have been walked,
     * so the walk never visits an entry it appended.
     *
     * @param value           the current value: an object, an array, or a scalar
     * @param membersAreNames whether {@code value}'s member names are names rather than keywords, so that it
     *     is a name container rather than a schema node, and a member named like a literal keyword is still a
     *     schema
     */
    private static void rewriteNode(Object value, boolean membersAreNames) {
        if (value instanceof JsonObject object) {
            for (String field : object.fieldNames()) {
                if (!membersAreNames && WebValidationStrategy.LITERAL_KEYWORDS.contains(field)) {
                    continue;
                }
                Object member = object.getValue(field);
                boolean namedMembers = !membersAreNames
                        && WebValidationStrategy.NAMED_MEMBER_KEYWORDS.contains(field)
                        && member instanceof JsonObject;
                rewriteNode(member, namedMembers);
            }
            if (!membersAreNames) {
                guardSchemaNode(object);
            }
        } else if (value instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                rewriteNode(array.getValue(index), false);
            }
        }
    }

    /**
     * Rewrites one schema node of the copy: one bound entry for a string {@code pattern}, one for a bounded
     * {@code format}, and then the {@code propertyNames} entry for a non-empty {@code patternProperties},
     * appended in that order to the node's {@code allOf}, which is created when absent. A node whose {@code
     * allOf} is present but not an array is left unchanged, so the engine judges it exactly as it judges the
     * original.
     *
     * <p>This is the one per-node step of the rewrite, so a further rewrite of the node's {@code format}
     * belongs here too.
     *
     * @param node the schema node, a member of the copy
     */
    private static void guardSchemaNode(JsonObject node) {
        JsonArray entries = new JsonArray();
        if (node.getValue(WebValidationStrategy.PATTERN_KEYWORD) instanceof String) {
            entries.add(boundEntry());
        }
        if (node.getValue(FORMAT_KEYWORD) instanceof String format && BOUNDED_FORMATS.contains(format)) {
            entries.add(boundEntry());
        }
        if (node.getValue(WebValidationStrategy.PATTERN_PROPERTIES_KEYWORD) instanceof JsonObject byPattern
                && !byPattern.isEmpty()) {
            entries.add(new JsonObject().put(PROPERTY_NAMES_KEYWORD, boundEntry()));
        }
        if (entries.isEmpty()) {
            return;
        }
        if (!node.containsKey(ALL_OF_KEYWORD)) {
            node.put(ALL_OF_KEYWORD, entries);
        } else if (node.getValue(ALL_OF_KEYWORD) instanceof JsonArray allOf) {
            allOf.addAll(entries);
        }
    }

    /**
     * Returns a new bound entry, {@code {"format": "x-vertique-pattern-input-bound"}}.
     *
     * @return the entry
     */
    private static JsonObject boundEntry() {
        return new JsonObject().put(FORMAT_KEYWORD, BOUND_FORMAT);
    }

    /**
     * Rewrites a copy of {@code schema} and compiles it with this guard as the format validator; {@code
     * schema} itself is never compiled or modified.
     *
     * @param schema  the shared schema
     * @param options the gate's schema options
     * @return the guarded validator
     */
    Validator compile(JsonObject schema, JsonSchemaOptions options) {
        return Validator.create(JsonSchema.of(rewrite(schema)), options, this);
    }

    /**
     * Applies the bound at {@link #BOUND_FORMAT} and delegates every other format, {@code null} included, to
     * {@link JsonFormatValidator#DEFAULT_VALIDATOR}.
     *
     * <p>At the bound entry, a string of length {@code L} (UTF-16 code units) longer than the per-string limit
     * is rejected; otherwise, inside a window, {@code L} is added to the request's running total, which is
     * rejected once it exceeds the per-request limit. Outside a window only the per-string limit applies. A
     * non-string instance passes.
     *
     * @param instanceType the instance's JSON type
     * @param format       the format name, or {@code null} for a node without one
     * @param instance     the instance
     * @return {@code null} when the instance passes, else the default validator's message
     * @throws PatternInputTooLong        when a string at the bound entry is longer than the per-string limit
     * @throws PatternInputTotalExceeded when a string at the bound entry takes the request's total past the
     *     per-request limit
     */
    @Override
    public String validateFormat(String instanceType, String format, Object instance) {
        if (BOUND_FORMAT.equals(format)) {
            if (instance instanceof String text) {
                bound(text.length());
            }
            return null;
        }
        return JsonFormatValidator.DEFAULT_VALIDATOR.validateFormat(instanceType, format, instance);
    }

    /**
     * Applies both limits to one string at the bound entry.
     *
     * @param length the string's length in UTF-16 code units
     * @throws PatternInputTooLong        when {@code length} exceeds the per-string limit
     * @throws PatternInputTotalExceeded when adding {@code length} takes the running total past the
     *     per-request limit
     */
    private void bound(int length) {
        if (length > maxChars) {
            throw new PatternInputTooLong(maxChars);
        }
        long[] spent = tally.get();
        if (spent == null) {
            return;
        }
        spent[0] += length;
        if (spent[0] > maxTotalChars) {
            throw new PatternInputTotalExceeded(maxTotalChars);
        }
    }

    /**
     * Opens the calling thread's per-request counting window; closing it removes the tally. Opening never
     * resets a tally already present, so closing the window is the only reset.
     *
     * @return the window
     */
    Window openWindow() {
        if (tally.get() == null) {
            tally.set(new long[1]);
        }
        return tally::remove;
    }

    /** A thread-confined per-request counting window; {@link #close()} removes the tally. */
    interface Window extends AutoCloseable {

        /** Removes the calling thread's tally. */
        @Override
        void close();
    }

    /**
     * Thrown at the bound entry for a string longer than the per-string limit. Stack-trace-free; its message
     * names the limit only, never the value.
     */
    static final class PatternInputTooLong extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The per-string limit the string exceeded. */
        private final int maxChars;

        /**
         * Creates the exception.
         *
         * @param maxChars the per-string limit
         */
        PatternInputTooLong(int maxChars) {
            super("pattern input exceeds the maximum length of " + maxChars + " characters", null, false, false);
            this.maxChars = maxChars;
        }

        /**
         * Returns the per-string limit the string exceeded.
         *
         * @return the limit
         */
        int maxChars() {
            return maxChars;
        }
    }

    /**
     * Thrown at the bound entry when a string takes the request's running total past the per-request limit.
     * Stack-trace-free; its message names the limit only, never the value.
     */
    static final class PatternInputTotalExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The per-request limit the total exceeded. */
        private final int maxTotalChars;

        /**
         * Creates the exception.
         *
         * @param maxTotalChars the per-request limit
         */
        PatternInputTotalExceeded(int maxTotalChars) {
            super(
                    "pattern input exceeds the maximum total length of " + maxTotalChars + " characters",
                    null,
                    false,
                    false);
            this.maxTotalChars = maxTotalChars;
        }

        /**
         * Returns the per-request limit the total exceeded.
         *
         * @return the limit
         */
        int maxTotalChars() {
            return maxTotalChars;
        }
    }
}
