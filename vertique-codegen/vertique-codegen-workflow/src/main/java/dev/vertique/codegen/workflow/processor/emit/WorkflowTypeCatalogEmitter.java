// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.TypeVisibility;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.workflow.processor.scan.TypeCatalogScanner.Entry;
import dev.vertique.codegen.workflow.processor.scan.TypeCatalogScanner.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates the {@code GeneratedWorkflowTypeCatalogModule} Dagger module: one
 * {@code @Provides @IntoSet} method that returns a {@code WorkflowTypeCatalog} listing the
 * {@code @WorkflowContract} interfaces and {@code @WorkflowType} classes of the compilation.
 *
 * <p>Entries are ordered by kind (contracts first) and then by name, so the generated source does not
 * depend on the order the compiler visits the classes in.
 *
 * <p>The package is resolved by {@link GeneratedModulePackage} from all the classes before any is
 * left out, so leaving a class out never moves the module. A class the module cannot name from its
 * package (not public, or nested in a non-public type, in another package; or in the unnamed package)
 * is left out of the catalog with a mandatory compiler warning, and is not mentioned in the generated
 * source.
 *
 * <p>When a class with the module's name already resolves, for example because a dependency generated
 * a module with the same name, the emitter reports an error and writes nothing.
 */
public final class WorkflowTypeCatalogEmitter {

    /** Simple name of the generated Dagger module. */
    static final String MODULE_SIMPLE_NAME = "GeneratedWorkflowTypeCatalogModule";

    private static final String PROVIDES_METHOD_NAME = "workflowTypeCatalog";

    private static final ClassName CATALOG = ClassName.get("dev.vertique.workflow.types", "WorkflowTypeCatalog");
    private static final ClassName CATALOG_ENTRY =
            ClassName.get("dev.vertique.workflow.types", "WorkflowTypeCatalogEntry");
    private static final ClassName ENTRY_KIND =
            ClassName.get("dev.vertique.workflow.types", "WorkflowTypeCatalogEntry", "Kind");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::kind)
            .thenComparing(Entry::name)
            .thenComparing(e -> e.type().getQualifiedName().toString());

    private final CodegenContext ctx;

    /**
     * Constructs a {@code WorkflowTypeCatalogEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public WorkflowTypeCatalogEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the catalog module for the given entries.
     *
     * <p>Returns without writing when {@code entries} is empty or when every class had to be left
     * out.
     *
     * @param entries the contract and type entries of the compilation; must not be {@code null}
     */
    public void emit(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        // Resolved from every class before any is filtered out, so leaving one out never relocates the module.
        String pkg = GeneratedModulePackage.resolvePackage(
                ctx, entries.stream().map(Entry::type).toList());
        ClassName moduleName = ClassName.get(pkg, MODULE_SIMPLE_NAME);

        List<Entry> listed = referenceableFrom(entries, pkg);
        if (listed.isEmpty()) {
            return;
        }
        // Checked once, before this emitter writes, so a module it wrote in an earlier round of the
        // same compilation is never taken for a collision.
        if (ctx.elements().getTypeElement(moduleName.canonicalName()) != null) {
            ctx.diagnostics()
                    .error(
                            listed.get(0).type(),
                            Diagnostics.workflowCatalogModuleCollision(
                                    moduleName.canonicalName(), CodegenContext.OPTION_OUTPUT_PACKAGE));
            return;
        }

        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();
        writer.addIntoSetProvidesWithBody(CATALOG, PROVIDES_METHOD_NAME, body(listed));

        JavaFile file = writer.build();
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            listed.get(0).type(),
                            "Failed to write generated module %s: %s",
                            moduleName.canonicalName(),
                            e.getMessage());
        }
    }

    // --- Internal helpers ---

    private static CodeBlock body(List<Entry> listed) {
        CodeBlock entries = listed.stream()
                .sorted(ENTRY_ORDER)
                .map(entry -> CodeBlock.of(
                        "new $T($T.$L, $S, $T.class)",
                        CATALOG_ENTRY,
                        ENTRY_KIND,
                        entry.kind().name(),
                        entry.name(),
                        ClassName.get(entry.type())))
                .collect(CodeBlock.joining(",\n"));
        return CodeBlock.builder()
                .add("return () -> $T.of(\n$>$>", LIST)
                .add(entries)
                .add(");\n$<$<")
                .build();
    }

    /**
     * Filters the entries down to the classes the module can name, warning once per class left out.
     *
     * @param entries       all the entries of the compilation
     * @param modulePackage the already-resolved package the module will be written to
     * @return the entries the module may list; possibly empty
     */
    private List<Entry> referenceableFrom(List<Entry> entries, String modulePackage) {
        List<Entry> listed = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            TypeElement type = entry.type();
            if (TypeVisibility.isReferenceableFrom(type, modulePackage)) {
                listed.add(entry);
                continue;
            }
            ctx.diagnostics()
                    .mandatoryWarning(
                            type,
                            Diagnostics.workflowTypeNotCatalogued(
                                    entry.kind() == Kind.CONTRACT ? "@WorkflowContract" : "@WorkflowType",
                                    type.getQualifiedName().toString(),
                                    modulePackage,
                                    MODULE_SIMPLE_NAME,
                                    CodegenContext.OPTION_OUTPUT_PACKAGE));
        }
        return listed;
    }
}
