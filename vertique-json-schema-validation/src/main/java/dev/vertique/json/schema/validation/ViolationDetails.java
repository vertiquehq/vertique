// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import dev.vertique.core.validation.ViolationDetail;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.pointer.JsonPointer;
import io.vertx.json.schema.OutputUnit;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates a JSON Schema validation result into value-free {@link ViolationDetail} entries.
 *
 * <p>Every detail names the failed keyword as its {@code type}, the constraint the schema declares as its
 * {@code args}, and a canonical message built from that keyword and constraint alone. The submitted value, and
 * the validation engine's own message, which may quote it, never reach a detail, with the single exception of the
 * {@code required} message, which names the missing property and no value. A detail whose keyword cannot be
 * resolved, and a result whose errors are all structural wrappers, yield one fixed value-free detail instead.
 *
 * <p>A reported location is cut back to the part the schema itself declares, so text a client chose, such as the
 * key of an undeclared property, never appears in a path. The one name appended to such a location is a missing
 * required member, and it is taken from the schema's own {@code required} array.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract.
 * It is a stateless utility; every member is {@code static}.
 */
public final class ViolationDetails {

    /**
     * The fixed message of a value-free detail. It is a literal rather than a formatted message, so no keyword
     * argument and no submitted value can reach a response through it.
     */
    public static final String VALUE_FREE_MESSAGE = "does not satisfy the schema";

    /** The detail {@code type} of a string rejected by the per-string pattern-input limit. */
    public static final String PATTERN_INPUT_LENGTH_TYPE = "patternInputLength";

    /** The detail {@code type} of a validation rejected by the per-validation pattern-input limit. */
    public static final String PATTERN_INPUT_TOTAL_LENGTH_TYPE = "patternInputTotalLength";

    /**
     * The number of local {@code $ref} hops followed while deciding whether an instance-location segment names
     * something the schema declares. A longer chain ends the run, so a self-referential document bounds the walk.
     */
    private static final int MAX_LOCAL_REF_HOPS = 16;

    /**
     * The number of subschemas examined while deciding whether one instance-location segment is declared. A
     * composition wider or deeper than this ends the run, so a self-referential composition bounds the walk.
     */
    private static final int MAX_COMPOSED_SUBSCHEMAS = 64;

    /** The most digits an instance-location segment may have and still be named as an array index. */
    private static final int MAX_INDEX_DIGITS = 10;

    /** The applicators whose branches may each declare a name at the same instance location. */
    private static final List<String> COMPOSITION_KEYWORDS = List.of("allOf", "anyOf", "oneOf");

    /**
     * The keywords whose constraint value is a scalar the schema declares, read from the schema node that carries
     * the failing keyword. Any other keyword, except {@link #TYPE_KEYWORD}, resolves to {@code {keyword: true}}.
     */
    private static final Set<String> SCALAR_CONSTRAINT_KEYWORDS = Set.of(
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "pattern",
            "minItems",
            "maxItems",
            "minProperties",
            "maxProperties",
            "multipleOf");

    /** The {@code type} keyword, whose declared value is a type name or a list of type names. */
    private static final String TYPE_KEYWORD = "type";

    /** The engine's message for an omitted required member, up to the opening quote of the member's name. */
    private static final String MISSING_REQUIRED_PREFIX = "Instance does not have required property \"";

    /** The only schema that makes an {@code anyOf} entry the absent half of an optional value. */
    private static final JsonObject NULL_BRANCH = new JsonObject().put(TYPE_KEYWORD, "null");

    private ViolationDetails() {}

    /**
     * Translates a validation result into one detail per concrete violation.
     *
     * <p>Structural wrapper errors, which the engine emits beside the concrete violations they contain, are
     * skipped, so each detail refers to a concrete violated constraint keyword. A keyword with its constraint
     * resolved is described by a canonical message; the engine's message is never used for it.
     *
     * <p>An error whose keyword location is absent or empty, rather than naming a structural keyword, has no
     * keyword to describe. It yields a value-free detail at the error's own location, on the single-error branch
     * and the multi-error branch alike, without consulting the engine's message;
     * {@link #safeDetail(String, Map, String)} itself answers a {@code null} keyword
     * with the fixed, value-free message {@code is invalid}.
     *
     * <p>A result that is not valid always yields at least one detail: when every error it reported was
     * structural, exactly one value-free detail is returned, at the location of the first reported error.
     *
     * <p>The path of a detail is the engine's instance location, cut back to the part the schema declares, in the
     * requested form. A result without a nested error list is itself the one error and, when its keyword is
     * resolved, is located at the root, spelled as the empty string.
     *
     * <p>The engine reports an omitted {@code required} member at its parent. That detail's path is the parent's
     * followed by the member's name when the schema confirms the name: it is an entry of the {@code required}
     * array at the unit's keyword location, the reported location needed no cutting back, and the keyword
     * location passes through no {@code oneOf} or {@code not} and no {@code anyOf} other than a two-entry one whose
     * other entry is exactly {@code {"type":"null"}}, which only makes the value optional. Otherwise the parent is
     * the path, so one of several alternatives is never reported as the member that is missing. The name is
     * escaped as one pointer segment, never decoded, and is never text the client sent.
     *
     * @param result   the engine's result
     * @param schema   the schema the result was produced for, used to resolve constraint values and to tell a
     *     declared name from a client-chosen key; may be {@code null}
     * @param failFast when {@code true}, stops after the first concrete or unresolved error
     * @param form     the spelling of each detail's path
     * @return the details, empty for a valid result
     */
    public static List<ViolationDetail> collect(OutputUnit result, JsonObject schema, boolean failFast, PathForm form) {
        return collectUpTo(result, schema, failFast, form, Integer.MAX_VALUE);
    }

    /**
     * Translates a validation result into at most {@code limit + 1} details, and stops walking the result once it
     * holds that many.
     *
     * <p>The details are the first {@code limit + 1} that {@link #collect(OutputUnit, JsonObject, boolean,
     * PathForm)} returns, in its order, so a result of {@code limit} violations or fewer yields all of them and a
     * longer one yields {@code limit} and one more, which tells a caller the list was cut without it having
     * converted the rest. The walk over a very long error list stops at that point; the engine's own work to produce the list is
     * not bounded by it.
     *
     * @param result   the engine's result
     * @param schema   the schema the result was produced for; may be {@code null}
     * @param failFast when {@code true}, stops after the first concrete or unresolved error
     * @param form     the spelling of each detail's path
     * @param limit    the most details the caller will list, not negative
     * @return the details, empty for a valid result, at most {@code limit + 1} long
     * @throws IllegalArgumentException if {@code limit} is negative
     */
    public static List<ViolationDetail> collect(
            OutputUnit result, JsonObject schema, boolean failFast, PathForm form, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        return collectUpTo(result, schema, failFast, form, limit == Integer.MAX_VALUE ? limit : limit + 1);
    }

    /** The shared walk: stops once it holds {@code stopAt} details, or after the first when {@code failFast}. */
    private static List<ViolationDetail> collectUpTo(
            OutputUnit result, JsonObject schema, boolean failFast, PathForm form, int stopAt) {
        List<ViolationDetail> failures = new ArrayList<>();
        if (result.getValid() != null && result.getValid()) {
            return failures;
        }
        List<OutputUnit> errors = result.getErrors();
        if (errors == null || errors.isEmpty()) {
            collectSingle(result, schema, form, failures);
            return failures;
        }
        for (OutputUnit error : errors) {
            String keyword = SchemaKeywords.constraintKeyword(error);
            if (keyword == null && SchemaKeywords.isStructuralLocation(error.getKeywordLocation())) {
                continue;
            }
            if (keyword == null) {
                failures.add(valueFreeAt(error.getInstanceLocation(), schema, form));
                if (failFast || failures.size() >= stopAt) {
                    return failures;
                }
                continue;
            }
            Map<String, Object> args = resolveConstraintArgs(keyword, error.getKeywordLocation(), schema);
            String message = safeDetail(keyword, args, error.getError());
            // Cut back exactly as the value-free detail is: a violation under an undeclared key is reported at a
            // location ending in the client's own key, which must never reach a response.
            String declared = declaredLocation(error.getInstanceLocation(), schema);
            String detailPath = path(declared, form);
            String missing = "required".equals(keyword) ? omittedRequiredMember(error, declared, schema) : null;
            failures.add(new ViolationDetail(
                    missing == null ? detailPath : member(detailPath, missing, form),
                    message,
                    keyword,
                    args.isEmpty() ? null : args));
            if (failFast || failures.size() >= stopAt) {
                return failures;
            }
        }
        if (failures.isEmpty()) {
            failures.add(valueFreeAt(errors.get(0).getInstanceLocation(), schema, form));
        }
        return failures;
    }

    /**
     * Builds the value-free detail for a path: the fixed message, and neither a type nor arguments.
     *
     * @param path the path the detail names, already in its final form
     * @return the detail
     */
    public static ViolationDetail valueFree(String path) {
        return new ViolationDetail(path, VALUE_FREE_MESSAGE, null, null);
    }

    /**
     * Produces a message that does not echo the submitted value.
     *
     * <p>When the keyword's constraint is resolved, the message is a canonical description built from the
     * keyword and the constraint alone, for example {@code must have a minimum length of 3}. The engine's message
     * is used only for {@code required}, which names the missing property and no value; {@code required} falls
     * back to {@code is missing a required field} when there is no engine message. Every other keyword,
     * including one this method does not know and one whose constraint could not be resolved from {@code args},
     * renders as {@code "<keyword> constraint violated"}, so an unreviewed keyword fails closed and the engine's
     * message, whose wording may quote the submitted value, is never returned for it. A {@code null} keyword
     * yields the fixed, value-free {@code is invalid} message whatever the engine's message says.
     *
     * @param keyword    the failed keyword, or {@code null} when it cannot be resolved
     * @param args       the resolved constraint arguments, empty when the constraint could not be resolved
     * @param rawMessage the engine's message, or {@code null}; used only for {@code required}
     * @return the message
     */
    public static String safeDetail(String keyword, Map<String, Object> args, String rawMessage) {
        if (keyword == null) {
            return "is invalid";
        }
        if ("required".equals(keyword)) {
            return rawMessage != null ? rawMessage : "is missing a required field";
        }
        if (args != null && args.containsKey(keyword)) {
            Object constraintValue = args.get(keyword);
            return switch (keyword) {
                case "minLength" -> "must have a minimum length of " + constraintValue;
                case "maxLength" -> "must have a maximum length of " + constraintValue;
                case "minimum" -> "must be at least " + constraintValue;
                case "maximum" -> "must be at most " + constraintValue;
                case "exclusiveMinimum" -> "must be greater than " + constraintValue;
                case "exclusiveMaximum" -> "must be less than " + constraintValue;
                case "minItems" -> "must have at least " + constraintValue + " items";
                case "maxItems" -> "must have at most " + constraintValue + " items";
                case "minProperties" -> "must have at least " + constraintValue + " properties";
                case "maxProperties" -> "must have at most " + constraintValue + " properties";
                case "multipleOf" -> "must be a multiple of " + constraintValue;
                case "pattern" -> "must match pattern: " + constraintValue;
                case "type" -> typeDetail(constraintValue);
                // The engine's messages for these two quote the client's own property name, and for
                // patternProperties the generated expression, so they are given fixed messages.
                case "propertyNames" -> "contains a property name the schema does not allow";
                case "patternProperties" -> VALUE_FREE_MESSAGE;
                // The catch-all must never fall back to the engine's message, whose wording is not reviewed and
                // may quote the submitted value: an unknown keyword renders as a known-unsafe one does.
                default -> keyword + " constraint violated";
            };
        }
        return keyword + " constraint violated";
    }

    /**
     * Maps a pattern-input guard failure to its detail.
     *
     * <p>The failure is recognised when {@code thrown} is a guard exception or its direct cause is one; the cause
     * is checked once, not down a chain. The detail
     * has an empty path, the type {@value #PATTERN_INPUT_LENGTH_TYPE} or {@value #PATTERN_INPUT_TOTAL_LENGTH_TYPE},
     * a message naming the configured limit, and the limit as the {@code limit} argument. The value, the key and
     * the pattern never appear.
     *
     * @param thrown the exception a validation call threw
     * @return the detail, or {@link Optional#empty()} when neither {@code thrown} nor its direct cause is a
     *     guard exception
     */
    public static Optional<ViolationDetail> patternInputBound(Throwable thrown) {
        Throwable guardFailure = isGuardFailure(thrown) ? thrown : thrown.getCause();
        if (guardFailure instanceof PatternInputGuard.PatternInputTooLong tooLong) {
            return Optional.of(new ViolationDetail(
                    "",
                    "exceeds the maximum length of " + tooLong.maxChars() + " characters for pattern validation",
                    PATTERN_INPUT_LENGTH_TYPE,
                    Map.of("limit", tooLong.maxChars())));
        }
        if (guardFailure instanceof PatternInputGuard.PatternInputTotalExceeded totalExceeded) {
            return Optional.of(new ViolationDetail(
                    "",
                    "exceeds the maximum total length of " + totalExceeded.maxTotalChars()
                            + " characters for pattern validation",
                    PATTERN_INPUT_TOTAL_LENGTH_TYPE,
                    Map.of("limit", totalExceeded.maxTotalChars())));
        }
        return Optional.empty();
    }

    /**
     * Converts an engine instance location into an RFC 6901 JSON pointer.
     *
     * <p>One leading {@code #} is removed and each segment is percent-decoded as UTF-8; an escape that is not
     * well formed, including one written with a non-ASCII digit, is left as written. So is an escape of a byte
     * the engine never percent-escapes, such as a letter or a digit, exactly as it was written: the engine
     * cannot have written it, and the engine's one-digit escape of a byte below {@code 0x10} would otherwise
     * let a client key read as that byte. The pointer escapes
     * {@code ~0} and {@code ~1} are kept. The root, in any
     * spelling ({@code #}, the empty string, or {@code null}), becomes the empty string; {@code /} and
     * {@code #/} are the member with the empty name and become {@code /}.
     *
     * @param engineLocation the engine's instance location, for example {@code #/a%20b/0/a~1b}; may be
     *     {@code null}
     * @return the pointer, for example {@code /a b/0/a~1b}; the empty string for the root
     */
    public static String rfc6901(String engineLocation) {
        if (engineLocation == null) {
            return "";
        }
        String pointer = engineLocation.startsWith("#") ? engineLocation.substring(1) : engineLocation;
        if (pointer.isEmpty()) {
            return "";
        }
        StringBuilder converted = new StringBuilder(pointer.length());
        String[] segments = pointer.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                converted.append('/');
            }
            converted.append(percentDecode(segments[index]));
        }
        return converted.toString();
    }

    // --- collection ---

    /**
     * Collects the details of a result that has no nested error list: the result is itself the one error, as for
     * a boolean {@code false} schema. A resolved keyword is located at the root.
     */
    private static void collectSingle(
            OutputUnit result, JsonObject schema, PathForm form, List<ViolationDetail> failures) {
        String keyword = SchemaKeywords.constraintKeyword(result);
        if (keyword == null) {
            // Either a structural wrapper, which carries no concrete detail, or a location that names no keyword,
            // whose engine message is not safe to pass on: both yield the value-free detail.
            failures.add(valueFreeAt(result.getInstanceLocation(), schema, form));
            return;
        }
        Map<String, Object> args = resolveConstraintArgs(keyword, result.getKeywordLocation(), schema);
        failures.add(new ViolationDetail(
                path(null, form), safeDetail(keyword, args, result.getError()), keyword, args.isEmpty() ? null : args));
    }

    /**
     * Builds a value-free detail at an instance location, cut back to the part the schema declares.
     */
    private static ViolationDetail valueFreeAt(String instanceLocation, JsonObject schema, PathForm form) {
        return valueFree(path(declaredLocation(instanceLocation, schema), form));
    }

    /** Spells a location in the requested form; a missing location is the empty string. */
    private static String path(String location, PathForm form) {
        return form == PathForm.RFC6901 ? rfc6901(location) : (location == null ? "" : location);
    }

    /**
     * Appends a member name to a parent path already spelled in {@code form}. The RFC 6901 form escapes the name
     * as one pointer segment, {@code ~} first and then {@code /}, and leaves it otherwise as written; the fragment
     * form spells it as the engine spells a location segment. A name that cannot be spelled leaves the parent.
     */
    private static String member(String parent, String name, PathForm form) {
        if (form == PathForm.RFC6901) {
            return parent + '/' + name.replace("~", "~0").replace("/", "~1");
        }
        try {
            String segment = JsonPointer.create().append(name).toURI().toString();
            return segment.startsWith("#/") ? parent + segment.substring(1) : parent;
        } catch (RuntimeException e) {
            return parent;
        }
    }

    private static boolean isGuardFailure(Throwable thrown) {
        return thrown instanceof PatternInputGuard.PatternInputTooLong
                || thrown instanceof PatternInputGuard.PatternInputTotalExceeded;
    }

    // --- omitted required member ---

    /**
     * The name of the member a {@code required} unit reports as omitted, or {@code null} when the schema does not
     * confirm it and the unit stays at its parent.
     *
     * <p>The name is the engine's message with its fixed prefix and the final quote removed. It is returned only
     * when it is an entry of the {@code required} array the keyword location leads to, so the text that enters a
     * path is the schema's own, and only when {@code declared}, the reported location after the cut-back, is the
     * reported location itself: a parent under a key the client chose is not extended.
     */
    private static String omittedRequiredMember(OutputUnit error, String declared, JsonObject schema) {
        String message = error.getError();
        if (schema == null
                || message == null
                || message.length() <= MISSING_REQUIRED_PREFIX.length()
                || !message.startsWith(MISSING_REQUIRED_PREFIX)
                || !message.endsWith("\"")
                || !Objects.equals(declared, error.getInstanceLocation())) {
            return null;
        }
        String name = message.substring(MISSING_REQUIRED_PREFIX.length(), message.length() - 1);
        JsonObject node = requiredOwner(schema, error.getKeywordLocation());
        return node != null && node.getValue("required") instanceof JsonArray required && required.contains(name)
                ? name
                : null;
    }

    /**
     * Navigates a {@code required} keyword location to the subschema that carries the keyword, or {@code null}
     * when a step cannot be resolved or the location is not one whose unit may be named.
     *
     * <p>A {@code oneOf} or {@code not} step ends the walk, as does an {@code anyOf} step after a {@code $ref}
     * step or one whose array is not a null branch beside the branch the location enters: the unit is then one
     * of several alternatives. A {@code $ref} step is followed when it is local.
     */
    private static JsonObject requiredOwner(JsonObject root, String keywordLocation) {
        if (keywordLocation == null) {
            return null;
        }
        String location = keywordLocation.startsWith("#") ? keywordLocation.substring(1) : keywordLocation;
        String keywordStep = "/required";
        if (!location.endsWith(keywordStep)) {
            return null;
        }
        String path = location.substring(0, location.length() - keywordStep.length());
        if (path.isEmpty()) {
            return root;
        }
        Object current = root;
        boolean followedRef = false;
        boolean enteredAnyOf = false;
        String[] segments = path.split("/", -1);
        for (int position = 0; position < segments.length; position++) {
            String segment = segments[position];
            if (position == 0 && segment.isEmpty()) {
                continue;
            }
            Object next;
            if (current instanceof JsonArray array) {
                long index = arrayIndex(segment);
                if (index < 0 || index >= array.size()) {
                    return null;
                }
                if (enteredAnyOf) {
                    enteredAnyOf = false;
                    if (array.size() != 2 || !NULL_BRANCH.equals(array.getValue(1 - (int) index))) {
                        return null;
                    }
                }
                next = array.getValue((int) index);
            } else if (current instanceof JsonObject object) {
                String name = decodeSegment(segment);
                if (name == null || "oneOf".equals(name) || "not".equals(name)) {
                    return null;
                }
                next = object.getValue(name);
                if ("anyOf".equals(name)) {
                    if (followedRef || !(next instanceof JsonArray)) {
                        return null;
                    }
                    enteredAnyOf = true;
                } else if ("$ref".equals(name)) {
                    if (!(next instanceof String ref) || !ref.startsWith("#")) {
                        return null;
                    }
                    followedRef = true;
                    next = navigateSchema(root, ref.substring(1));
                }
            } else {
                return null;
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current instanceof JsonObject owner && !enteredAnyOf ? owner : null;
    }

    // --- constraint resolution ---

    /**
     * Resolves the constraint a failed keyword declares.
     *
     * <p>For a scalar keyword the value is the schema node at the keyword location; for {@code type} it is the
     * declared type name, or the declared list of type names. A keyword whose value cannot be resolved yields
     * {@code {keyword: true}}. The submitted value is never included.
     */
    private static Map<String, Object> resolveConstraintArgs(
            String keyword, String keywordLocation, JsonObject schema) {
        if (schema == null || keywordLocation == null || keyword == null) {
            return keyword != null ? Map.of(keyword, Boolean.TRUE) : Map.of();
        }
        boolean isType = TYPE_KEYWORD.equals(keyword);
        if (!isType && !SCALAR_CONSTRAINT_KEYWORDS.contains(keyword)) {
            return Map.of(keyword, Boolean.TRUE);
        }
        String normalizedLocation = keywordLocation.startsWith("#") ? keywordLocation.substring(1) : keywordLocation;
        String parentPath = normalizedLocation.substring(0, normalizedLocation.length() - keyword.length());
        if (parentPath.endsWith("/")) {
            parentPath = parentPath.substring(0, parentPath.length() - 1);
        }
        JsonObject node = navigateKeywordLocation(schema, parentPath);
        if (node == null) {
            return Map.of(keyword, Boolean.TRUE);
        }
        Object value = node.getValue(keyword);
        if (isType) {
            value = declaredType(value);
        }
        if (value == null) {
            return Map.of(keyword, Boolean.TRUE);
        }
        return Map.of(keyword, value);
    }

    /**
     * Normalizes a declared {@code type}: a name stays a string, and a non-empty list of names becomes an
     * immutable list. Any other shape yields {@code null}, so the caller falls back to the placeholder.
     */
    private static Object declaredType(Object value) {
        if (value instanceof String name) {
            return name;
        }
        if (value instanceof JsonArray array && !array.isEmpty()) {
            List<String> names = new ArrayList<>(array.size());
            for (Object element : array) {
                if (!(element instanceof String name)) {
                    return null;
                }
                names.add(name);
            }
            return List.copyOf(names);
        }
        return null;
    }

    /**
     * Navigates a schema along a keyword location the engine reported, returning the subschema that carries the
     * failing keyword, or {@code null} when it cannot be reached.
     *
     * <p>A keyword location indexes the arrays of {@code allOf}, {@code anyOf}, {@code oneOf} and
     * {@code prefixItems}, passes through a {@code $ref} as the segment {@code $ref} and continues inside the
     * referenced subschema, and spells each property name escaped and percent-encoded. Each is followed here. A
     * {@code $ref} is followed only when it is local.
     */
    private static JsonObject navigateKeywordLocation(JsonObject root, String path) {
        Object current = root;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            Object next = null;
            if (current instanceof JsonArray array) {
                long index = arrayIndex(segment);
                next = index >= 0 && index < array.size() ? array.getValue((int) index) : null;
            } else if (current instanceof JsonObject object) {
                String name = decodeSegment(segment);
                next = name == null ? null : object.getValue(name);
                if ("$ref".equals(name) && next instanceof String ref) {
                    next = ref.startsWith("#") ? navigateSchema(root, ref.substring(1)) : null;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current instanceof JsonObject object ? object : null;
    }

    /**
     * Navigates a schema along a slash-delimited path of object members, returning the object there, or
     * {@code null} when navigation fails. An empty path or {@code /} returns the schema itself.
     */
    private static JsonObject navigateSchema(JsonObject schema, String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return schema;
        }
        JsonObject current = schema;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            Object next = current.getValue(segment);
            if (next instanceof JsonObject object) {
                current = object;
            } else {
                return null;
            }
        }
        return current;
    }

    // --- declared-location cut-back ---

    /**
     * Cuts a reported instance location back to its longest leading run of segments the schema itself declares,
     * so a detail names a failing location without naming any text the client chose.
     *
     * <p>An undeclared property under a closed object is reported at a location whose last segment is the
     * client's own key, of whatever length the client sent. The reported location is therefore named where its
     * last segment is a name the schema declares, and otherwise the containing location is named, up to the
     * root. What survives is spelled entirely by the schema's own names, so its length is fixed by the schema and
     * not by the request.
     *
     * <p>A segment is declared when a {@code properties} entry, a {@code prefixItems} position or an
     * {@code items} schema at that point names it, at the subschema reached so far or in any {@code allOf},
     * {@code anyOf} or {@code oneOf} branch of it. The engine reports each segment escaped and percent-encoded,
     * so a segment is decoded before it is looked up, and the reported spelling is what the path keeps. A segment
     * counts as an array index only when it is a plausible one. Only the empty element before the first
     * {@code /} is skipped: a later empty segment is the member named {@code ""}, looked up like any other name.
     *
     * <p>Resolution fails closed: a segment that cannot be shown to be declared, under an unresolvable
     * {@code $ref}, a composition past the subschema bound, a malformed encoding, an escape of a byte the engine
     * never escapes, or a {@code null} schema, ends the run, which names a shorter location and never a longer one.
     */
    private static String declaredLocation(String instanceLocation, JsonObject schema) {
        if (instanceLocation == null || instanceLocation.isEmpty()) {
            return instanceLocation;
        }
        String anchor = instanceLocation.startsWith("#") ? "#" : "";
        String pointer = instanceLocation.substring(anchor.length());
        if (pointer.isEmpty()) {
            return instanceLocation;
        }
        StringBuilder declared = new StringBuilder(anchor);
        List<JsonObject> nodes = schema == null ? List.of() : List.of(schema);
        String[] segments = pointer.split("/", -1);
        for (int position = 0; position < segments.length; position++) {
            String segment = segments[position];
            if (position == 0 && segment.isEmpty()) {
                continue;
            }
            String name = decodeSegment(segment);
            if (name == null) {
                break;
            }
            List<JsonObject> children = declaredChildren(nodes, segment, name, schema);
            if (children.isEmpty()) {
                break;
            }
            declared.append('/').append(segment);
            nodes = children;
        }
        return declared.toString();
    }

    /**
     * The subschemas a declared segment leads to: every {@code properties} entry of that name, and for a
     * plausible array index every {@code prefixItems} position or {@code items} schema other than {@code false}
     * it falls under, gathered across the subschemas reached so far and each of their composition branches.
     *
     * <p>Only a name the schema spells itself matches, never {@code additionalProperties} or
     * {@code patternProperties}, which admit keys the client chose. A declared segment whose subschema is a
     * boolean resolves to the empty object, which declares nothing further.
     */
    private static List<JsonObject> declaredChildren(
            List<JsonObject> nodes, String segment, String name, JsonObject root) {
        List<JsonObject> children = new ArrayList<>();
        for (JsonObject node : composedSubschemas(nodes, root)) {
            JsonObject properties = node.getJsonObject("properties");
            if (properties != null && properties.containsKey(name)) {
                children.add(asSubschema(properties.getValue(name)));
                continue;
            }
            long index = arrayIndex(segment);
            if (index < 0) {
                continue;
            }
            Object prefixItems = node.getValue("prefixItems");
            int prefixSize = prefixItems instanceof JsonArray tuple ? tuple.size() : 0;
            Object items = node.getValue("items");
            if (index < prefixSize) {
                children.add(asSubschema(((JsonArray) prefixItems).getValue((int) index)));
            } else if (items != null && !Boolean.FALSE.equals(items)) {
                // `items: false` closes the tuple: no index past it is declared.
                children.add(asSubschema(items));
            }
        }
        return children;
    }

    /**
     * Expands the subschemas reached into each one's local {@code $ref} target and, transitively, every
     * {@code allOf}, {@code anyOf} and {@code oneOf} branch, bounded by {@link #MAX_COMPOSED_SUBSCHEMAS}.
     *
     * <p>A branch whose reference cannot be followed contributes nothing. Past the bound the whole expansion is
     * abandoned and the run ends.
     */
    private static List<JsonObject> composedSubschemas(List<JsonObject> nodes, JsonObject root) {
        List<JsonObject> expanded = new ArrayList<>();
        Set<Map<String, Object>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<JsonObject> pending = new ArrayDeque<>(nodes);
        while (!pending.isEmpty()) {
            JsonObject resolved = resolveLocalRef(pending.poll(), root);
            if (resolved == null || !seen.add(resolved.getMap())) {
                continue;
            }
            if (expanded.size() == MAX_COMPOSED_SUBSCHEMAS) {
                return List.of();
            }
            expanded.add(resolved);
            for (String keyword : COMPOSITION_KEYWORDS) {
                if (resolved.getValue(keyword) instanceof JsonArray branches) {
                    for (Object branch : branches) {
                        if (branch instanceof JsonObject object) {
                            pending.add(object);
                        }
                    }
                }
            }
        }
        return expanded;
    }

    /** A declared subschema as an object: a boolean subschema becomes the empty object. */
    private static JsonObject asSubschema(Object subschema) {
        return subschema instanceof JsonObject object ? object : new JsonObject();
    }

    /**
     * Decodes one instance-location segment as the engine reports it, percent-encoded UTF-8 over an escaped
     * name, back to the property name the schema would declare.
     *
     * <p>A segment holding an escape of a byte the engine never escapes is not trusted, see
     * {@link #neverEscapedByEngine(int)}, and is treated as not well formed.
     *
     * @return the decoded name, or {@code null} when the segment is not a well-formed encoding
     */
    private static String decodeSegment(String segment) {
        if (segment.indexOf('%') < 0 && segment.indexOf('~') < 0) {
            return segment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int index = 0; index < segment.length(); index++) {
            char c = segment.charAt(index);
            if (c == '%') {
                if (index + 2 >= segment.length()) {
                    return null;
                }
                int value = hexEscapeAt(segment, index);
                if (value < 0 || neverEscapedByEngine(value)) {
                    return null;
                }
                bytes.write(value);
                index += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        String escaped;
        try {
            escaped = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        StringBuilder name = new StringBuilder(escaped.length());
        for (int index = 0; index < escaped.length(); index++) {
            char c = escaped.charAt(index);
            if (c != '~') {
                name.append(c);
                continue;
            }
            char next = index + 1 < escaped.length() ? escaped.charAt(index + 1) : 0;
            if (next == '0') {
                name.append('~');
            } else if (next == '1') {
                name.append('/');
            } else {
                return null;
            }
            index++;
        }
        return name.toString();
    }

    /**
     * Follows a local {@code $ref} chain to the subschema it names, within {@link #MAX_LOCAL_REF_HOPS} hops so a
     * self-referential document cannot spin.
     *
     * @return the referenced subschema, or {@code null} when the chain cannot be followed
     */
    private static JsonObject resolveLocalRef(JsonObject node, JsonObject root) {
        JsonObject current = node;
        for (int hop = 0; hop < MAX_LOCAL_REF_HOPS; hop++) {
            if (current == null || root == null) {
                return current;
            }
            Object ref = current.getValue("$ref");
            if (!(ref instanceof String pointer) || !pointer.startsWith("#")) {
                return current;
            }
            current = navigateSchema(root, pointer.substring(1));
        }
        return null;
    }

    /**
     * Reads an instance-location segment as a plausible array index: the canonical spelling of a non-negative
     * integer, with no leading zero except {@code 0} itself, of at most {@link #MAX_INDEX_DIGITS} digits. Under
     * a composition that mixes an array branch with an open-object branch a client key can be all digits, so
     * the digit bound limits what such a key can contribute to a path.
     *
     * @return the index, or {@code -1} when the segment is not a plausible index
     */
    private static long arrayIndex(String segment) {
        int length = segment.length();
        if (length == 0 || length > MAX_INDEX_DIGITS || (length > 1 && segment.charAt(0) == '0')) {
            return -1;
        }
        for (int index = 0; index < length; index++) {
            char c = segment.charAt(index);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Long.parseLong(segment);
    }

    // --- messages and pointer decoding ---

    /**
     * Renders a {@code type} message from the declared type: {@code must be of type: string} for one type, and
     * {@code must be of type: string or null} or {@code must be of type: string, integer or null} for a list.
     * When the declared type could not be resolved, the message names no type.
     */
    private static String typeDetail(Object constraintValue) {
        if (constraintValue instanceof String name) {
            return "must be of type: " + name;
        }
        if (constraintValue instanceof List<?> names && !names.isEmpty()) {
            int last = names.size() - 1;
            String head = names.subList(0, last).stream().map(String::valueOf).collect(Collectors.joining(", "));
            return "must be of type: " + (last == 0 ? names.get(0) : head + " or " + names.get(last));
        }
        return "must be of the required type";
    }

    /**
     * Percent-decodes one pointer segment as UTF-8. A run of escapes that does not decode to well-formed UTF-8,
     * and a {@code %} not followed by two hexadecimal digits, are left as written. So is an escape of a byte the
     * engine never escapes, see {@link #neverEscapedByEngine(int)}; it does not join the run of escapes beside it.
     */
    private static String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        StringBuilder decoded = new StringBuilder(segment.length());
        int index = 0;
        while (index < segment.length()) {
            if (hexEscapeAt(segment, index) < 0) {
                decoded.append(segment.charAt(index));
                index++;
                continue;
            }
            if (neverEscapedByEngine(hexEscapeAt(segment, index))) {
                decoded.append(segment, index, index + 3);
                index += 3;
                continue;
            }
            int start = index;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value;
            while (index < segment.length()
                    && (value = hexEscapeAt(segment, index)) >= 0
                    && !neverEscapedByEngine(value)) {
                bytes.write(value);
                index += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException e) {
                decoded.append(segment, start, index);
            }
        }
        return decoded.toString();
    }

    /**
     * The byte a {@code %XX} escape at {@code index} spells, or {@code -1} when there is no such escape. Only
     * ASCII hexadecimal digits of either case are accepted; a full-width or other Unicode digit is not.
     */
    private static int hexEscapeAt(String text, int index) {
        if (text.charAt(index) != '%' || index + 2 >= text.length()) {
            return -1;
        }
        int high = asciiHexValue(text.charAt(index + 1));
        int low = asciiHexValue(text.charAt(index + 2));
        return high < 0 || low < 0 ? -1 : high * 16 + low;
    }

    /**
     * Whether the engine never percent-escapes the byte: an ASCII letter or digit, and the ASCII punctuation the
     * engine writes as itself. A slash and a tilde are in the set, as the engine spells them {@code ~1} and
     * {@code ~0}. Control characters, DEL, the space, and the characters it does escape, such as the quote, the
     * percent sign, the angle brackets, the square brackets and the braces, are not.
     *
     * <p>An escape of such a byte cannot have come from the engine, so it is read as text the client wrote, never
     * as the byte. The engine writes a byte below {@code 0x10} with one hexadecimal digit, so a client key such as
     * a control character followed by {@code Eame} is located as {@code %6Eame}, and a decoder that honoured that
     * escape would read the byte {@code n} and let the key forge a declared name.
     */
    private static boolean neverEscapedByEngine(int value) {
        return value == 0x21
                || value == 0x23
                || value == 0x24
                || (value >= 0x26 && value <= 0x3B)
                || value == 0x3D
                || (value >= 0x3F && value <= 0x5A)
                || value == 0x5F
                || (value >= 0x61 && value <= 0x7A)
                || value == 0x7E;
    }

    /** The value of an ASCII hexadecimal digit ({@code 0-9}, {@code a-f}, {@code A-F}), or {@code -1}. */
    private static int asciiHexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
