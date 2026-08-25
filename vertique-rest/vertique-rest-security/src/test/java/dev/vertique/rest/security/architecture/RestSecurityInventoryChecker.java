// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.architecture;

import io.vertx.core.json.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Module-local public-surface reflection helper for {@link RestSecurityInventoryGuardTest}.
 *
 * <p>This checker is deliberately duplicated per guarded module rather than shared (T006's frozen
 * decision): no single artifact is on every guarded module's test classpath, and a cross-repository
 * test-jar would be a published-artifact and release-surface change MCP-001 has no mandate to make.
 * All module-specific knowledge lives in the module's own inventory resource, so the duplication
 * carries no design decision.
 *
 * <p>It scans only {@code vertique-rest-security}'s own compiled output, compares at full generic
 * signature rather than erased descriptor, and records hand-authored public surface only.
 */
final class RestSecurityInventoryChecker {

    private final Path classesDir;
    private final Path sourceRoot;
    private final List<Class<?>> moduleClasses;

    RestSecurityInventoryChecker(Class<?> anchor) {
        this.classesDir = codeSourceOf(anchor);
        this.sourceRoot = classesDir.getParent().getParent().resolve(Path.of("src", "main", "java"));
        this.moduleClasses = loadModuleClasses(anchor.getClassLoader());
    }

    /** Every package declared by this module, public or not. */
    Set<String> scannedPackages() {
        return moduleClasses.stream().map(Class::getPackageName).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Full generic signatures of every public constructor, method, and field declared by a
     * hand-authored public type of this module.
     */
    Set<String> scannedSignatures() {
        Set<String> signatures = new TreeSet<>();
        for (Class<?> type : moduleClasses) {
            if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || !handAuthored(type)) {
                continue;
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (isPublicAndReal(constructor.getModifiers(), constructor.isSynthetic())) {
                    signatures.add(constructor.toGenericString());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (isPublicAndReal(method.getModifiers(), method.isSynthetic())) {
                    signatures.add(method.toGenericString());
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (isPublicAndReal(field.getModifiers(), field.isSynthetic())) {
                    signatures.add(field.toGenericString());
                }
            }
        }
        return signatures;
    }

    /** Exact binary names of every hand-authored public type exported by this module. */
    Set<String> scannedTypes() {
        return moduleClasses.stream()
                .filter(type -> Modifier.isPublic(type.getModifiers()) && !type.isSynthetic() && handAuthored(type))
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Hand-authored public types of this module that extend a <strong>non-public</strong> type of the
     * same module.
     *
     * <p>Such a supertype's public members are consumer-reachable through the public subtype, but the
     * recorded set is built from declared, non-synthetic members, and javac surfaces the inherited
     * member on the subtype as a <em>bridge</em> — which is synthetic, and therefore filtered out. So
     * neither the supertype's own row (it is not public, so it is never scanned) nor the subtype's row
     * records it, and the member escapes the guard entirely.
     *
     * <p>The check is structural rather than member-level for exactly that reason: a declaring-class
     * comparison cannot see the escape, because reflection reports the bridge as declared by the
     * public subtype. Nothing in the guarded modules does this today; the check exists because the
     * mechanism is frozen for seven modules and the remaining task graph.
     *
     * @return the offending {@code subtype extends supertype} pairs, empty when the surface is clean
     */
    Set<String> publicTypesWithNonPublicModuleSupertypes() {
        Set<String> offenders = new TreeSet<>();
        for (Class<?> type : moduleClasses) {
            if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || !handAuthored(type)) {
                continue;
            }
            for (Class<?> supertype = type.getSuperclass();
                    supertype != null && supertype != Object.class;
                    supertype = supertype.getSuperclass()) {
                if (!Modifier.isPublic(supertype.getModifiers()) && moduleClasses.contains(supertype)) {
                    offenders.add(type.getName() + " extends " + supertype.getName());
                }
            }
        }
        return offenders;
    }

    /**
     * Top-level types excluded from the scan as annotation-processor output, so an exclusion can be
     * asserted rather than trusted.
     *
     * @return the excluded binary names, empty when nothing was excluded
     */
    Set<String> excludedAsGenerated() {
        Set<String> excluded = new TreeSet<>();
        for (Class<?> type : moduleClasses) {
            if (Modifier.isPublic(type.getModifiers())
                    && !type.isSynthetic()
                    && !type.getName().contains("$")
                    && !handAuthored(type)) {
                excluded.add(type.getName());
            }
        }
        return excluded;
    }

    static JsonObject recordedInventory(String resourcePath) {
        try (InputStream stream =
                RestSecurityInventoryChecker.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("inventory resource not on the test classpath: " + resourcePath);
            }
            return new JsonObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable inventory resource: " + resourcePath, e);
        }
    }

    static Set<String> recordedSignatures(JsonObject inventory) {
        JsonObject types = inventory.getJsonObject("types");
        Set<String> signatures = new TreeSet<>();
        for (String type : types.fieldNames()) {
            types.getJsonArray(type).forEach(signature -> signatures.add((String) signature));
        }
        return signatures;
    }

    /** Exact public type identities recorded as inventory keys, including empty marker/module types. */
    static Set<String> recordedTypes(JsonObject inventory) {
        return new TreeSet<>(inventory.getJsonObject("types").fieldNames());
    }

    static Set<String> recordedPackages(JsonObject inventory) {
        Set<String> packages = new TreeSet<>();
        inventory.getJsonArray("packages").forEach(name -> packages.add((String) name));
        return packages;
    }

    /**
     * Annotation-processor output carries no source file and is not guarded surface (T006 § Frozen
     * guard mechanism and scan scope). Nested types are judged by their outer type, so generated
     * members of a hand-authored type stay in scope.
     */
    private boolean handAuthored(Class<?> type) {
        String binaryName = type.getName();
        int nested = binaryName.indexOf('$');
        String outer = nested < 0 ? binaryName : binaryName.substring(0, nested);
        return Files.isRegularFile(sourceRoot.resolve(outer.replace('.', '/') + ".java"));
    }

    private static boolean isPublicAndReal(int modifiers, boolean synthetic) {
        return Modifier.isPublic(modifiers) && !synthetic;
    }

    private List<Class<?>> loadModuleClasses(ClassLoader loader) {
        try (Stream<Path> tree = Files.walk(classesDir)) {
            return tree.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> classesDir.relativize(path).toString())
                    .map(name ->
                            name.substring(0, name.length() - ".class".length()).replace(File.separatorChar, '.'))
                    .filter(name -> !name.endsWith("module-info") && !name.endsWith("package-info"))
                    .sorted()
                    .<Class<?>>map(name -> load(name, loader))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable module output directory: " + classesDir, e);
        }
    }

    private static Class<?> load(String binaryName, ClassLoader loader) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled class missing from the test classpath: " + binaryName, e);
        }
    }

    private static Path codeSourceOf(Class<?> anchor) {
        try {
            Path location = Path.of(
                    anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(location)) {
                throw new IllegalStateException("expected this module's own output directory, found: " + location);
            }
            return location;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("uninterpretable code source for " + anchor.getName(), e);
        }
    }
}
