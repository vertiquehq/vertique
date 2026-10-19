// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import jakarta.annotation.Nullable;

/**
 * How the path of a {@link ViolationDetail} is written.
 *
 * <p>A transport reads the form to decide whether a path may be reported as a location. New values
 * may be added in a later release, so a consumer that switches on this type keeps a default branch
 * and treats a value it does not recognize like {@link #JAVA_PROPERTY_PATH}.
 *
 * <p>A pointer is relative to the document its producer validated and is built with the producer's
 * own mapper. It is not a pointer into the body of any request: only the code that knows where the
 * validated document sits in the request can say so, for example when the route binds names
 * differently or nests the document.
 *
 * <p>{@link #accepts(String)} tells whether a path is well formed for a form. A producer emits only
 * accepted paths, and a transport reports a pointer only when this form accepts it.
 */
public enum ViolationPathForm {

    /**
     * A Java property path of the validated object, such as {@code items[0].name}, or any other path
     * that is not a pointer. It is not a location in any request and a transport does not render it as
     * one.
     */
    JAVA_PROPERTY_PATH,

    /**
     * An RFC 6901 JSON Pointer into the document the producer validated, written with the names its
     * mapper uses. A producer that declares this form guarantees that every detail path is either
     * {@code ""} (the whole document) or begins with {@code /}, and that no path contains a key the
     * client chose.
     */
    JSON_POINTER;

    /** The longest {@link #JSON_POINTER} path {@link #accepts(String)} admits, in characters. */
    private static final int MAX_POINTER_LENGTH = 2000;

    /**
     * Tells whether {@code path} is well formed for this form.
     *
     * <p>Every path is accepted for {@link #JAVA_PROPERTY_PATH}, {@code null} included. For {@link
     * #JSON_POINTER} a path is accepted only when it is {@code ""} or begins with {@code /}, every
     * {@code ~} in it is followed by {@code 0} or {@code 1}, it holds no ISO control character and
     * neither U+2028 nor U+2029, and it is at most 2,000 characters long. A {@code null} path is not
     * accepted.
     *
     * <p>New values may be added in a later release; a value this method does not know accepts every
     * path, like {@link #JAVA_PROPERTY_PATH}.
     *
     * @param path the detail path to check, may be {@code null}
     * @return {@code true} when the path is well formed for this form
     */
    public boolean accepts(@Nullable String path) {
        return this != JSON_POINTER || isWellFormedPointer(path);
    }

    private static boolean isWellFormedPointer(@Nullable String path) {
        if (path == null) {
            return false;
        }
        int length = path.length();
        if (length > MAX_POINTER_LENGTH) {
            return false;
        }
        if (length > 0 && path.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = path.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
                return false;
            }
            if (c == '~') {
                i++;
                if (i == length || (path.charAt(i) != '0' && path.charAt(i) != '1')) {
                    return false;
                }
            }
        }
        return true;
    }
}
