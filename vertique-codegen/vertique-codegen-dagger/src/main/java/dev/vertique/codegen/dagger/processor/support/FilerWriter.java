// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.support;

import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.CodegenContext;
import java.io.IOException;

/**
 * Shared helper for writing a {@link JavaFile} to a processing environment's
 * {@link javax.annotation.processing.Filer} with consistent error reporting.
 *
 * <p>Both emitters in this module use the same try/catch pattern; consolidating it here keeps the
 * error wording aligned across generated-source kinds.
 */
public final class FilerWriter {

    private FilerWriter() {}

    /**
     * Writes the given file via the context's {@code Filer}; on {@link IOException}, emits an
     * {@link dev.vertique.codegen.Diagnostics#error} attributed to no element with the failing
     * FQN included in the message.
     *
     * @param file the file to write; must not be {@code null}
     * @param ctx  the codegen context whose {@code Filer} and {@code Diagnostics} are used
     * @param fqn  the fully-qualified name of the file being written (for the error message)
     */
    public static void write(JavaFile file, CodegenContext ctx, String fqn) {
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write generated source file '%s': %s", fqn, e.getMessage());
        }
    }
}
