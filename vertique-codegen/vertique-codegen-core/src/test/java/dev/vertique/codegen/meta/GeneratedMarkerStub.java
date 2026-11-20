// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

/**
 * In-memory source of the class-file marker for tests that compile generated code without
 * {@code vertique-core} on the classpath. It mirrors the runtime declaration's retention and
 * targets, so the generated code must place the marker exactly where the real one allows it.
 */
final class GeneratedMarkerStub {

    /** The marker's class-file descriptor, as it appears in a {@code RuntimeInvisibleAnnotations} entry. */
    static final String DESCRIPTOR = "Ldev/vertique/core/codegen/Generated;";

    private GeneratedMarkerStub() {}

    static JavaFileObject source() {
        return new SimpleJavaFileObject(
                URI.create("string:///dev/vertique/core/codegen/Generated" + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        package dev.vertique.core.codegen;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;
                        @Retention(RetentionPolicy.CLASS)
                        @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
                        public @interface Generated {}
                        """;
            }
        };
    }

    /** Whether a compiled class file references the marker. */
    static boolean carriesMarker(byte[] classFile) {
        return new String(classFile, StandardCharsets.ISO_8859_1).contains(DESCRIPTOR);
    }
}
