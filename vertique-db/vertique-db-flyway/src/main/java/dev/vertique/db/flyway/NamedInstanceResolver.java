// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static dev.vertique.db.flyway.NamedMigrationFailure.CONFIGURATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.RESOURCE;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Resolves and validates the named instances of a {@link FlywayMigrationRunner} without opening a
 * database connection.
 *
 * <p>Validation runs in a fixed order. Every check that needs only the configuration and the
 * declarations (names, the dependency graph, the timeout, the mode ceiling, credential pairs, the
 * {@code db.schemas} agreement, location syntax and known history collisions) runs first. Only then,
 * unless the root mode is {@link FlywayMode#DISABLED}, are migration resources looked up on the
 * caller's class loader and the database support selected by the root JDBC destination asked to
 * apply its own rules. Nothing is cached: every call validates afresh.
 */
final class NamedInstanceResolver {

    /** Instance name reserved for the single entry a legacy run reports. */
    static final String LEGACY_NAME = "legacy";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final String DEFAULT_TABLE_PREFIX = "flyway_schema_history_";
    private static final String DEFAULT_BASELINE_VERSION = "1";
    private static final long MIN_LOCK_TIMEOUT_MS = 1L;
    private static final long MAX_LOCK_TIMEOUT_MS = 300_000L;
    private static final String CLASSPATH = "classpath:";
    private static final String FILESYSTEM = "filesystem:";

    private final FlywayConfig config;
    private final DbPoolConfig pool;
    private final List<MigrationDefinition> definitions;
    private final DbSchemas schemas;
    private final DatabaseSupportSelector selector;
    private final Supplier<String> jdbcUrl;

    /**
     * Creates a resolver.
     *
     * @param config the root Flyway configuration in named mode
     * @param pool the pool configuration, for the credential fallback
     * @param definitions the wired migration declarations
     * @param schemas the neutral schema placement
     * @param selector the database-support selector
     * @param jdbcUrl resolves the root JDBC destination; throws {@link IllegalStateException} when none
     *     is configured
     */
    NamedInstanceResolver(
            FlywayConfig config,
            DbPoolConfig pool,
            List<MigrationDefinition> definitions,
            DbSchemas schemas,
            DatabaseSupportSelector selector,
            Supplier<String> jdbcUrl) {
        this.config = config;
        this.pool = pool;
        this.definitions = definitions;
        this.schemas = schemas;
        this.selector = selector;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Resolves and validates the named instances.
     *
     * @param loader the caller's class loader, used for classpath location lookup
     * @return the validated plan
     * @throws NamedMigrationFailure when the configuration or the declarations are invalid
     */
    NamedPlan resolve(ClassLoader loader) {
        FlywayMode root = config.mode();
        if (root == null) {
            throw NamedMigrationFailure.of(CONFIGURATION, null, "flyway.mode must not be null in named mode");
        }
        Map<String, FlywayInstanceConfig> overrides = overrides();
        Map<String, MigrationDefinition> declared = declarations();
        for (String name : overrides.keySet()) {
            if (!declared.containsKey(name)) {
                throw NamedMigrationFailure.of(
                        CONFIGURATION, name, "the override matches no wired migration declaration");
            }
        }
        List<MigrationDefinition> ordered = order(declared);
        if (ordered.isEmpty() && root != FlywayMode.DISABLED) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION,
                    null,
                    "named mode needs at least one wired migration declaration unless the root mode is DISABLED");
        }
        long timeout = config.coordinationLockTimeoutMs();
        if (timeout < MIN_LOCK_TIMEOUT_MS || timeout > MAX_LOCK_TIMEOUT_MS) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION,
                    null,
                    "flyway.coordinationLockTimeoutMs must be between " + MIN_LOCK_TIMEOUT_MS + " and "
                            + MAX_LOCK_TIMEOUT_MS);
        }
        if ((config.user() == null) != (config.password() == null)) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION, null, "flyway.user and flyway.password must be set together or not at all");
        }

        List<Resolved> resolved = new ArrayList<>();
        for (MigrationDefinition definition : ordered) {
            resolved.add(resolveInstance(definition, overrides.get(definition.name()), root));
        }
        List<MigrationInstanceView> views =
                resolved.stream().map(r -> r.instance().view()).toList();
        checkHistoryIdentities(views);
        checkClasspathOverlap(resolved, views);

        List<NamedPlan.Instance> instances =
                resolved.stream().map(Resolved::instance).toList();
        if (root == FlywayMode.DISABLED) {
            return new NamedPlan(instances, null, null, null, null);
        }

        checkResources(resolved, loader, views);
        String url;
        try {
            url = jdbcUrl.get();
        } catch (IllegalStateException ex) {
            throw NamedMigrationFailure.withViews(
                    CONFIGURATION, null, "either flyway.jdbcUrl or db.host and db.database must be configured", views);
        }
        DatabaseSupport support = select(url, views)
                .orElseThrow(() -> NamedMigrationFailure.withViews(
                        CONFIGURATION,
                        null,
                        "named instances are not supported for the configured database destination",
                        views));
        try {
            support.validateConfiguration(views);
        } catch (MigrationRunException ex) {
            throw NamedMigrationFailure.fromBackend(ex, views);
        } catch (RuntimeException ex) {
            throw rejectedBySupport(views);
        }
        boolean rootPair = config.user() != null && config.password() != null;
        return new NamedPlan(
                instances,
                url,
                support,
                rootPair ? config.user() : pool.user(),
                rootPair ? config.password() : pool.password());
    }

    /** Asks the selector; a selector that breaks its contract is reported without its own failure. */
    private Optional<DatabaseSupport> select(String url, List<MigrationInstanceView> views) {
        Optional<DatabaseSupport> selected;
        try {
            selected = selector.select(url);
        } catch (RuntimeException ex) {
            throw rejectedBySupport(views);
        }
        return selected == null ? Optional.empty() : selected;
    }

    private static NamedMigrationFailure rejectedBySupport(List<MigrationInstanceView> views) {
        return NamedMigrationFailure.withViews(
                CONFIGURATION, null, "the database support rejected the configuration", views);
    }

    // --- Names and graph ---

    private Map<String, FlywayInstanceConfig> overrides() {
        Map<String, FlywayInstanceConfig> overrides = new LinkedHashMap<>();
        for (FlywayInstanceConfig override : config.instances()) {
            String name = override.name();
            checkName(name, "override");
            if (overrides.putIfAbsent(name, override) != null) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "the instance has more than one override");
            }
        }
        return overrides;
    }

    private Map<String, MigrationDefinition> declarations() {
        Map<String, MigrationDefinition> declared = new HashMap<>();
        for (MigrationDefinition definition : definitions) {
            String name = definition.name();
            checkName(name, "declaration");
            if (declared.putIfAbsent(name, definition) != null) {
                throw NamedMigrationFailure.of(
                        CONFIGURATION, name, "more than one migration declaration uses the name");
            }
        }
        return declared;
    }

    private static void checkName(String name, String kind) {
        if (!NAME.matcher(name).matches()) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION, name, "the " + kind + " name must match [a-z][a-z0-9-]{0,31}");
        }
        if (LEGACY_NAME.equals(name)) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "the " + kind + " name is reserved");
        }
    }

    /** Topological order with lexicographic tie-breaking; rejects unknown, self and cyclic dependencies. */
    private static List<MigrationDefinition> order(Map<String, MigrationDefinition> declared) {
        Map<String, Set<String>> dependents = new HashMap<>();
        Map<String, Integer> pending = new HashMap<>();
        for (MigrationDefinition definition : declared.values()) {
            String name = definition.name();
            Set<String> dependencies = new LinkedHashSet<>(definition.dependsOn());
            for (String dependency : dependencies) {
                if (dependency.equals(name)) {
                    throw NamedMigrationFailure.of(CONFIGURATION, name, "the declaration depends on itself");
                }
                if (!declared.containsKey(dependency)) {
                    throw NamedMigrationFailure.of(
                            CONFIGURATION, name, "the declaration depends on an undeclared instance");
                }
                dependents.computeIfAbsent(dependency, d -> new HashSet<>()).add(name);
            }
            pending.put(name, dependencies.size());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        pending.forEach((name, count) -> {
            if (count == 0) {
                ready.add(name);
            }
        });
        List<MigrationDefinition> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String name = ready.poll();
            ordered.add(declared.get(name));
            for (String dependent : dependents.getOrDefault(name, Set.of())) {
                if (pending.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() != declared.size()) {
            Set<String> cyclic = new TreeSet<>(declared.keySet());
            ordered.forEach(definition -> cyclic.remove(definition.name()));
            throw NamedMigrationFailure.of(
                    CONFIGURATION, null, "the declarations " + cyclic + " form a dependency cycle");
        }
        return ordered;
    }

    // --- One instance ---

    private Resolved resolveInstance(MigrationDefinition definition, FlywayInstanceConfig override, FlywayMode root) {
        String name = definition.name();
        Optional<FlywayInstanceConfig> o = Optional.ofNullable(override);

        FlywayMode mode = o.map(FlywayInstanceConfig::mode).orElse(root);
        if (permissiveness(mode) > permissiveness(root)) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION, name, "the mode " + mode + " exceeds the root mode ceiling " + root);
        }

        Credentials credentials = credentials(name, override);

        List<String> managed =
                managedSchemas(name, o.map(FlywayInstanceConfig::schemas).orElse(null));
        String schema = managed.isEmpty() ? null : managed.get(0);

        List<String> locations = o.map(FlywayInstanceConfig::locations).orElse(definition.locations());
        List<Location> parsed = parseLocations(name, locations);

        String table = o.map(FlywayInstanceConfig::table).orElse(DEFAULT_TABLE_PREFIX + name.replace('-', '_'));
        if (table.isBlank()) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "the history table must not be blank");
        }

        Map<String, String> placeholders = o.map(FlywayInstanceConfig::placeholders)
                .orElse(config.placeholders() == null ? Map.of() : config.placeholders());
        if (placeholders.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "placeholders must not contain null names or values");
        }

        MigrationInstanceView view = new MigrationInstanceView(
                name,
                schema,
                managed,
                table,
                locations,
                mode,
                o.map(FlywayInstanceConfig::target).orElse(config.target()),
                o.map(FlywayInstanceConfig::outOfOrder).orElse(config.outOfOrder()),
                o.map(FlywayInstanceConfig::baselineOnMigrate).orElse(config.baselineOnMigrate()),
                o.map(FlywayInstanceConfig::baselineVersion)
                        .orElse(Objects.requireNonNullElse(config.baselineVersion(), DEFAULT_BASELINE_VERSION)),
                o.map(FlywayInstanceConfig::validateOnMigrate).orElse(config.validateOnMigrate()),
                credentials.user(),
                credentials.source(),
                placeholders.keySet());
        return new Resolved(new NamedPlan.Instance(view, credentials.password(), placeholders), parsed);
    }

    private static int permissiveness(FlywayMode mode) {
        return switch (mode) {
            case DISABLED -> 0;
            case VALIDATE -> 1;
            case MIGRATE -> 2;
        };
    }

    /** Selects one complete credential pair: the instance's, else the root Flyway pair, else the pool pair. */
    private Credentials credentials(String name, FlywayInstanceConfig override) {
        if (override != null && (override.user() != null || override.password() != null)) {
            if (override.user() == null || override.password() == null) {
                throw NamedMigrationFailure.of(
                        CONFIGURATION, name, "the instance sets only one of user and password; set both or neither");
            }
            return new Credentials(override.user(), override.password(), CredentialSource.INSTANCE);
        }
        if (config.user() != null) {
            return new Credentials(config.user(), config.password(), CredentialSource.ROOT_FLYWAY);
        }
        return new Credentials(pool.user(), pool.password(), CredentialSource.DB_POOL);
    }

    /**
     * Resolves the effective managed schema list. With a {@code db.schemas} entry the entry comes first
     * and an explicit list must start with it; without one the explicit or inherited list is used as is.
     */
    private List<String> managedSchemas(String name, List<String> explicit) {
        List<String> source = explicit != null ? explicit : Objects.requireNonNullElse(config.schemas(), List.of());
        checkSchemaEntries(name, source);
        Optional<String> entry = schemas.schema(name);
        if (entry.isEmpty()) {
            return source;
        }
        String placement = entry.get();
        if (explicit != null) {
            if (explicit.isEmpty()) {
                throw NamedMigrationFailure.of(
                        CONFIGURATION,
                        name,
                        "an explicit empty schemas list conflicts with the instance's db.schemas entry");
            }
            if (!explicit.get(0).equals(placement)) {
                throw NamedMigrationFailure.of(
                        CONFIGURATION,
                        name,
                        "the first entry of the explicit schemas list must equal the instance's db.schemas entry");
            }
            return explicit;
        }
        List<String> managed = new ArrayList<>();
        managed.add(placement);
        source.stream().filter(s -> !s.equals(placement)).forEach(managed::add);
        return managed;
    }

    private static void checkSchemaEntries(String name, List<String> list) {
        Set<String> seen = new HashSet<>();
        for (String schema : list) {
            if (schema == null || schema.isBlank()) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "the schemas list contains a blank entry");
            }
            if (!seen.add(schema)) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "the schemas list contains a duplicate entry");
            }
        }
    }

    // --- Locations ---

    private static List<Location> parseLocations(String name, List<String> locations) {
        if (locations == null || locations.isEmpty()) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "the instance has no migration locations");
        }
        List<Location> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String location : locations) {
            Location candidate = parseLocation(name, location);
            if (!seen.add(candidate.key())) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "the instance lists the same location twice");
            }
            parsed.add(candidate);
        }
        return parsed;
    }

    private static Location parseLocation(String name, String location) {
        if (location == null || location.isBlank()) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "a location is blank");
        }
        boolean classpath = location.startsWith(CLASSPATH);
        if (!classpath && !location.startsWith(FILESYSTEM)) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION, name, "a location must use the classpath: or filesystem: scheme");
        }
        String path = location.substring(classpath ? CLASSPATH.length() : FILESYSTEM.length());
        if (path.indexOf('*') >= 0 || path.indexOf('?') >= 0) {
            throw NamedMigrationFailure.of(
                    CONFIGURATION, name, "a location contains a wildcard; locations must be literal directories");
        }
        if (classpath) {
            path = trimSlashes(path);
        }
        if (path.isBlank()) {
            throw NamedMigrationFailure.of(CONFIGURATION, name, "a location names no directory");
        }
        for (String segment : path.split(classpath ? "/" : "[/\\\\]", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "a location contains a dot segment");
            }
            if (classpath && segment.isEmpty()) {
                throw NamedMigrationFailure.of(CONFIGURATION, name, "a classpath location contains an empty segment");
            }
        }
        return new Location(name, classpath, path);
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    /** Rejects classpath locations of any instances that are equal or nested in one another. */
    private static void checkClasspathOverlap(List<Resolved> resolved, List<MigrationInstanceView> views) {
        List<Location> classpath = resolved.stream()
                .flatMap(r -> r.locations().stream())
                .filter(Location::classpath)
                .toList();
        for (int i = 0; i < classpath.size(); i++) {
            for (int j = i + 1; j < classpath.size(); j++) {
                Location a = classpath.get(i);
                Location b = classpath.get(j);
                if (nested(a.path(), b.path()) || nested(b.path(), a.path())) {
                    throw overlap(a.instance(), b.instance(), views);
                }
            }
        }
    }

    private static boolean nested(String outer, String inner) {
        return inner.equals(outer) || inner.startsWith(outer + "/");
    }

    /**
     * Confirms every location exists, resolving classpath directories on the caller's class loader
     * and filesystem directories to their real paths, and rejects filesystem locations that are equal
     * or nested after normalization.
     */
    private static void checkResources(List<Resolved> resolved, ClassLoader loader, List<MigrationInstanceView> views) {
        List<Location> filesystem = new ArrayList<>();
        List<Path> realPaths = new ArrayList<>();
        for (Resolved instance : resolved) {
            for (Location location : instance.locations()) {
                if (location.classpath()) {
                    if (!classpathDirectoryExists(loader, location.path())) {
                        throw NamedMigrationFailure.withViews(
                                RESOURCE, location.instance(), "a classpath location does not exist", views);
                    }
                } else {
                    filesystem.add(location);
                    realPaths.add(realDirectory(location, views));
                }
            }
        }
        for (int i = 0; i < realPaths.size(); i++) {
            for (int j = i + 1; j < realPaths.size(); j++) {
                Path a = realPaths.get(i);
                Path b = realPaths.get(j);
                if (a.startsWith(b) || b.startsWith(a)) {
                    throw overlap(
                            filesystem.get(i).instance(), filesystem.get(j).instance(), views);
                }
            }
        }
    }

    private static boolean classpathDirectoryExists(ClassLoader loader, String path) {
        try {
            return loader.getResources(path).hasMoreElements();
        } catch (IOException ex) {
            return false;
        }
    }

    private static Path realDirectory(Location location, List<MigrationInstanceView> views) {
        try {
            Path path = Path.of(location.path());
            if (Files.isDirectory(path)) {
                return path.toRealPath();
            }
        } catch (IOException | InvalidPathException ex) {
            // Reported below as a missing location.
        }
        throw NamedMigrationFailure.withViews(
                RESOURCE, location.instance(), "a filesystem location is not an existing directory", views);
    }

    private static NamedMigrationFailure overlap(String first, String second, List<MigrationInstanceView> views) {
        if (first.equals(second)) {
            return NamedMigrationFailure.withViews(
                    CONFIGURATION, first, "two locations of the instance overlap", views);
        }
        return NamedMigrationFailure.withViews(
                CONFIGURATION, null, "locations of the instances '" + first + "' and '" + second + "' overlap", views);
    }

    // --- History identities ---

    /** Rejects two instances whose known history schema and table are the same. */
    private static void checkHistoryIdentities(List<MigrationInstanceView> views) {
        Map<List<String>, String> owners = new HashMap<>();
        for (MigrationInstanceView view : views) {
            if (view.schema() == null) {
                continue;
            }
            String previous = owners.putIfAbsent(List.of(view.schema(), view.table()), view.name());
            if (previous != null) {
                throw NamedMigrationFailure.withViews(
                        CONFIGURATION,
                        null,
                        "the instances '" + previous + "' and '" + view.name()
                                + "' use the same history table in the same schema",
                        views);
            }
        }
    }

    // --- Internal values ---

    /** One parsed location of an instance. */
    private record Location(String instance, boolean classpath, String path) {

        String key() {
            return (classpath ? CLASSPATH : FILESYSTEM) + path;
        }
    }

    /** One resolved instance together with its parsed locations. */
    private record Resolved(NamedPlan.Instance instance, List<Location> locations) {}

    /** One selected credential pair; its rendering never includes the password. */
    private record Credentials(String user, String password, CredentialSource source) {

        @Override
        public String toString() {
            return "Credentials[user=" + user + ", source=" + source + "]";
        }
    }
}
