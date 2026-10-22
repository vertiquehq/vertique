// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class WorkflowPublicApiSnapshot {

    static final String BASELINE_REVISION = "f9ee3b2d7561b8265ed7acf1f474d16d7038062c";

    private static final List<String> SOURCE_ROOTS = List.of(
            "vertique-workflow/vertique-workflow-core/src/main/java",
            "vertique-workflow/vertique-workflow-engine/src/main/java",
            "vertique-workflow/vertique-workflow-definition/src/main/java",
            "vertique-workflow/vertique-workflow-postgresql/src/main/java");

    private WorkflowPublicApiSnapshot() {}

    static Path repositoryRoot() throws IOException, InterruptedException {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        CommandResult result = git(workingDirectory, "rev-parse", "--show-toplevel");
        requireSuccess(result, "locate the source repository");
        return Path.of(result.output().trim());
    }

    static List<String> capturePinnedBaseline(Path repositoryRoot) throws Exception {
        assertPinnedSources(repositoryRoot);
        List<Path> sourceFiles = trackedSourceFiles(repositoryRoot, BASELINE_REVISION);
        Set<String> publicTypeNames = publicTopLevelTypeNames(sourceFiles);
        if (publicTypeNames.isEmpty()) {
            throw new IllegalStateException("No authored public types were found in the pinned source paths");
        }
        return captureTypes(repositoryRoot, publicTypeNames);
    }

    static List<String> captureCurrentTypes(Path repositoryRoot, Collection<String> baselineLines) throws Exception {
        Set<String> baselineTypeNames = new TreeSet<>();
        for (String line : baselineLines) {
            if (line.startsWith("TYPE|")) {
                String[] columns = line.split("\\|", -1);
                if (columns.length < 3 || columns[1].isBlank()) {
                    throw new IllegalArgumentException("Invalid public type snapshot line: " + line);
                }
                baselineTypeNames.add(columns[1]);
            }
        }
        if (baselineTypeNames.isEmpty()) {
            throw new IllegalArgumentException("The baseline contains no public type entries");
        }
        return captureTypes(repositoryRoot, baselineTypeNames);
    }

    private static void assertPinnedSources(Path repositoryRoot) throws Exception {
        CommandResult head = git(repositoryRoot, "rev-parse", "HEAD");
        requireSuccess(head, "read the source revision");
        if (!BASELINE_REVISION.equals(head.output().trim())) {
            throw new IllegalStateException("Baseline capture requires source revision " + BASELINE_REVISION
                    + " but found " + head.output().trim());
        }

        List<String> diffCommand = new ArrayList<>(List.of("diff", "--quiet", BASELINE_REVISION, "--"));
        diffCommand.addAll(SOURCE_ROOTS);
        CommandResult diff = git(repositoryRoot, diffCommand.toArray(String[]::new));
        if (diff.exitCode() != 0) {
            throw new IllegalStateException("Baseline capture requires unchanged production source paths");
        }

        List<String> untrackedCommand = new ArrayList<>(List.of("ls-files", "--others", "--exclude-standard", "--"));
        untrackedCommand.addAll(SOURCE_ROOTS);
        CommandResult untracked = git(repositoryRoot, untrackedCommand.toArray(String[]::new));
        requireSuccess(untracked, "check for untracked production source files");
        if (!untracked.output().isBlank()) {
            throw new IllegalStateException("Baseline capture found untracked production source files: "
                    + untracked.output().trim());
        }
    }

    private static List<Path> trackedSourceFiles(Path repositoryRoot, String revision) throws Exception {
        List<String> command = new ArrayList<>(List.of("ls-tree", "-r", "-z", "--name-only", revision, "--"));
        command.addAll(SOURCE_ROOTS);
        CommandResult result = git(repositoryRoot, command.toArray(String[]::new));
        requireSuccess(result, "enumerate pinned production source paths");

        List<Path> paths = new ArrayList<>();
        Set<String> rootsFound = new HashSet<>();
        for (String relativePath : result.output().split("\u0000")) {
            if (relativePath.endsWith(".java")) {
                Path path = repositoryRoot.resolve(relativePath);
                if (!java.nio.file.Files.isRegularFile(path)) {
                    throw new IllegalStateException("Tracked production source path is missing: " + relativePath);
                }
                paths.add(path);
                SOURCE_ROOTS.stream()
                        .filter(relativePath::startsWith)
                        .findFirst()
                        .ifPresent(rootsFound::add);
            }
        }
        if (rootsFound.size() != SOURCE_ROOTS.size()) {
            throw new IllegalStateException(
                    "Pinned source inventory did not cover all four workflow modules: " + rootsFound);
        }
        paths.sort(Comparator.comparing(Path::toString));
        return paths;
    }

    private static Set<String> publicTopLevelTypeNames(List<Path> sourceFiles) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK compiler is required to enumerate authored public types");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Set<String> typeNames = new TreeSet<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sourceObjects = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            JavacTask task = (JavacTask)
                    compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, sourceObjects);
            int parsedUnits = 0;
            for (CompilationUnitTree compilationUnit : task.parse()) {
                parsedUnits++;
                String packageName = compilationUnit.getPackageName() == null
                        ? ""
                        : compilationUnit.getPackageName().toString();
                for (Tree declaration : compilationUnit.getTypeDecls()) {
                    if (declaration instanceof ClassTree classTree
                            && classTree.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)) {
                        String typeName = classTree.getSimpleName().toString();
                        typeNames.add(packageName.isEmpty() ? typeName : packageName + "." + typeName);
                    }
                }
            }
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .toList();
            if (parsedUnits != sourceFiles.size() || !errors.isEmpty()) {
                throw new IllegalStateException("Could not enumerate pinned Java source types: parsed " + parsedUnits
                        + " of " + sourceFiles.size() + " source files; errors=" + errors);
            }
        }
        return typeNames;
    }

    private static List<String> captureTypes(Path repositoryRoot, Collection<String> typeNames) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = WorkflowPublicApiSnapshot.class.getClassLoader();
        }

        Set<String> declarations = new TreeSet<>();
        Set<String> visitedTypes = new HashSet<>();
        for (String typeName : new TreeSet<>(typeNames)) {
            try {
                collectPublicType(Class.forName(typeName, false, classLoader), declarations, visitedTypes);
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IllegalStateException("An authored public type could not be loaded: " + typeName, failure);
            }
        }
        return List.copyOf(declarations);
    }

    private static void collectPublicType(Class<?> type, Set<String> declarations, Set<String> visitedTypes) {
        if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || !visitedTypes.add(type.getName())) {
            return;
        }

        declarations.add(typeDeclaration(type));
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic()) {
                declarations.add(constructorDeclaration(constructor));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isBridge() && !method.isSynthetic()) {
                declarations.add(methodDeclaration(method));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !field.isSynthetic()) {
                declarations.add(fieldDeclaration(field));
            }
        }
        for (Class<?> nestedType : type.getDeclaredClasses()) {
            collectPublicType(nestedType, declarations, visitedTypes);
        }
    }

    private static String typeDeclaration(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        String genericSuperclass =
                superclass == null ? "" : type.getGenericSuperclass().getTypeName();
        String genericInterfaces = typeList(List.of(type.getGenericInterfaces()));
        String enclosingType =
                type.getDeclaringClass() == null ? "" : type.getDeclaringClass().getName();
        return String.join(
                "|",
                "TYPE",
                type.getName(),
                typeKind(type),
                Modifier.toString(type.getModifiers()),
                typeVariables(type.getTypeParameters()),
                enclosingType,
                genericSuperclass,
                genericInterfaces);
    }

    private static String typeKind(Class<?> type) {
        if (type.isAnnotation()) {
            return "annotation";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return "interface";
        }
        return "class";
    }

    private static String constructorDeclaration(Constructor<?> constructor) {
        return String.join(
                "|",
                "CONSTRUCTOR",
                constructor.getDeclaringClass().getName(),
                Modifier.toString(constructor.getModifiers()),
                typeVariables(constructor.getTypeParameters()),
                typeList(List.of(constructor.getGenericParameterTypes())),
                typeList(List.of(constructor.getGenericExceptionTypes())),
                Boolean.toString(constructor.isVarArgs()));
    }

    private static String methodDeclaration(Method method) {
        String modifiers = Modifier.toString(method.getModifiers());
        if (method.isDefault()) {
            modifiers = modifiers.isEmpty() ? "default" : modifiers + " default";
        }
        return String.join(
                "|",
                "METHOD",
                method.getDeclaringClass().getName(),
                method.getName(),
                modifiers,
                typeVariables(method.getTypeParameters()),
                typeList(List.of(method.getGenericParameterTypes())),
                method.getGenericReturnType().getTypeName(),
                typeList(List.of(method.getGenericExceptionTypes())),
                Boolean.toString(method.isVarArgs()));
    }

    private static String fieldDeclaration(Field field) {
        String kind = field.isEnumConstant() ? "ENUM_CONSTANT" : "FIELD";
        return String.join(
                "|",
                kind,
                field.getDeclaringClass().getName(),
                field.getName(),
                Modifier.toString(field.getModifiers()),
                field.getGenericType().getTypeName());
    }

    private static String typeVariables(TypeVariable<?>[] typeVariables) {
        List<String> variables = new ArrayList<>();
        for (TypeVariable<?> variable : typeVariables) {
            List<String> bounds = List.of(variable.getBounds()).stream()
                    .map(Type::getTypeName)
                    .toList();
            variables.add(variable.getName() + " extends " + String.join("&", bounds));
        }
        return typeList(variables);
    }

    private static String typeList(Collection<? extends Type> types) {
        return typeList(types.stream().map(Type::getTypeName).toList());
    }

    private static String typeList(List<String> types) {
        return "[" + String.join(",", types) + "]";
    }

    private static CommandResult git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, new String(output, StandardCharsets.UTF_8));
    }

    private static void requireSuccess(CommandResult result, String operation) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException(
                    "Could not " + operation + ": " + result.output().trim());
        }
    }

    private record CommandResult(int exitCode, String output) {}
}
