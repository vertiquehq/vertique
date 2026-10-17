// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dagger.MembersInjector;
import dagger.internal.Factory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the sorted listing of public and protected signatures of the module's compiled classes and
 * reads and writes the recorded copy of that listing.
 *
 * <p>The listing covers every public or protected type under {@code dev.vertique.workflow} found in
 * {@code target/classes}: one line for the type header and one line per public or protected
 * constructor, method and field, each rendered with {@code toGenericString()}. Synthetic and bridge
 * members, {@code package-info} and Dagger-generated classes (and their nested classes) are left
 * out. Lines are sorted by natural {@code String} order and written with LF line endings.
 *
 * <p>Lines are normalized so that the listing does not depend on the JDK that renders it, see
 * {@link #normalize(String)}.
 */
final class SignatureListing {

    /** Classpath name of the recorded listing. */
    static final String RESOURCE_NAME = "public-signatures.txt";

    /** Location the record mode rewrites, relative to the Maven basedir. */
    static final Path RECORD_PATH = Path.of("src/test/resources", RESOURCE_NAME);

    private static final Path CLASSES_DIR = Path.of("target/classes");

    private static final String PACKAGE_PREFIX = "dev.vertique.workflow";

    /**
     * A {@code sealed} or {@code non-sealed} modifier in a type header. Newer JDKs print it in
     * {@code toGenericString()} while JDK 21 does not, so it is dropped from every line to keep one
     * recorded form valid on all JDKs.
     */
    private static final Pattern SEALED_MODIFIER =
            Pattern.compile("^((?:(?:public|protected|abstract|static|final|strictfp) )*)(?:non-)?sealed "
                    + "(?=(?:@?interface|class|record|enum) )");

    private SignatureListing() {}

    /**
     * The current listing and the names of the generated classes that were left out of it.
     *
     * @param lines sorted signature lines
     * @param generatedClassNames binary names of the skipped Dagger-generated classes, nested
     *     classes included
     */
    record Result(List<String> lines, Set<String> generatedClassNames) {}

    /** Walks the compiled classes of the module and builds the current listing. */
    static Result scan() {
        if (!Files.isDirectory(CLASSES_DIR)) {
            throw new IllegalStateException("Compiled classes not found at " + CLASSES_DIR.toAbsolutePath());
        }
        ClassLoader loader = SignatureListing.class.getClassLoader();
        Set<String> lines = new TreeSet<>();
        Set<String> generated = new TreeSet<>();
        for (String name : classNames()) {
            if (!name.startsWith(PACKAGE_PREFIX) || isPackageInfo(name)) {
                continue;
            }
            Class<?> type = load(name, loader);
            if (isGenerated(type)) {
                generated.add(name);
                continue;
            }
            if (type.isSynthetic() || !isPublicOrProtected(type.getModifiers())) {
                continue;
            }
            lines.add(type.toGenericString());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                addMember(lines, constructor);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isBridge()) {
                    addMember(lines, method);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                addMember(lines, field);
            }
        }
        Set<String> normalized = new TreeSet<>();
        for (String line : lines) {
            normalized.add(normalize(line));
        }
        return new Result(List.copyOf(normalized), Set.copyOf(generated));
    }

    /**
     * Removes the JDK-dependent rendering differences from one signature line.
     *
     * @param line a signature line as rendered by {@code toGenericString()} or read from the record
     * @return the line without a {@code sealed} or {@code non-sealed} modifier in a type header
     */
    static String normalize(String line) {
        return SEALED_MODIFIER.matcher(line).replaceFirst("$1");
    }

    /** Renders lines as the file content: one line each, LF terminated. */
    static String format(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Rewrites the recorded listing in the module basedir. */
    static void record(List<String> lines) {
        try {
            Files.createDirectories(RECORD_PATH.getParent());
            Files.writeString(RECORD_PATH, format(lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + RECORD_PATH.toAbsolutePath(), e);
        }
    }

    /**
     * Reads the recorded listing from the test classpath.
     *
     * @return the recorded content, exactly as stored
     * @throws AssertionError when the resource is missing, with the command that records it
     */
    static String readRecorded() {
        try (InputStream in = SignatureListing.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new AssertionError("Recorded listing '"
                        + RESOURCE_NAME
                        + "' is missing from the test classpath. Record it with "
                        + "-Dvertique.signatures.record=true on an unmodified tree and "
                        + "rerun the test.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE_NAME, e);
        }
    }

    /**
     * Splits file content into normalized lines; a single trailing line terminator adds no empty
     * line.
     */
    static List<String> lines(String content) {
        List<String> raw = new ArrayList<>(List.of(content.split("\n", -1)));
        if (!raw.isEmpty() && raw.get(raw.size() - 1).isEmpty()) {
            raw.remove(raw.size() - 1);
        }
        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            lines.add(normalize(line));
        }
        return lines;
    }

    private static void addMember(Set<String> lines, Member member) {
        if (member.isSynthetic() || !isPublicOrProtected(member.getModifiers())) {
            return;
        }
        if (member instanceof Constructor<?> constructor) {
            lines.add(constructor.toGenericString());
        } else if (member instanceof Method method) {
            lines.add(method.toGenericString());
        } else if (member instanceof Field field) {
            lines.add(field.toGenericString());
        }
    }

    private static boolean isPublicOrProtected(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static boolean isPackageInfo(String name) {
        return name.equals("package-info") || name.endsWith(".package-info");
    }

    private static boolean isGenerated(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (Factory.class.isAssignableFrom(c)
                    || MembersInjector.class.isAssignableFrom(c)
                    || c.getSimpleName().startsWith("Dagger")) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load compiled class " + name, e);
        }
    }

    private static List<String> classNames() {
        try (Stream<Path> files = Files.walk(CLASSES_DIR)) {
            return files.filter(Files::isRegularFile)
                    .map(CLASSES_DIR::relativize)
                    .map(Path::toString)
                    .filter(p -> p.endsWith(".class"))
                    .map(p -> p.substring(0, p.length() - ".class".length()))
                    .map(p -> p.replace('/', '.').replace('\\', '.'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + CLASSES_DIR.toAbsolutePath(), e);
        }
    }
}
