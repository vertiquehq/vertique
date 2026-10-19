// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.flyway.MigrationRunException.Stage;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Pattern;

/**
 * Resolves and validates the named instances of a {@link FlywayMigrationRunner} without opening a
 * database connection or looking up any migration resource.
 *
 * <p>Everything checked here needs only the configuration and the declarations: names, the dependency
 * graph, the mode ceiling, credential pairs, the {@code db.schemas} agreement, the lock retry count,
 * history identities and classpath locations that nest across instances. Whether a location exists is
 * left to Flyway, which fails a run on a missing location. Nothing is cached: every call validates
 * afresh.
 */
final class NamedInstanceResolver {

    /** Instance name reserved for the single entry a legacy run reports. */
    static final String LEGACY_NAME = "legacy";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final String DEFAULT_TABLE_PREFIX = "flyway_schema_history_";
    private static final String DEFAULT_BASELINE_VERSION = "1";
    private static final String CLASSPATH = "classpath:";

    private final FlywayConfig config;
    private final DbPoolConfig pool;
    private final List<MigrationDefinition> definitions;
    private final DbSchemas schemas;

    /**
     * Creates a resolver.
     *
     * @param config the root Flyway configuration in named mode
     * @param pool the pool configuration, for the credential fallback
     * @param definitions the wired migration declarations
     * @param schemas the neutral schema placement
     */
    NamedInstanceResolver(
            FlywayConfig config, DbPoolConfig pool, List<MigrationDefinition> definitions, DbSchemas schemas) {
        this.config = config;
        this.pool = pool;
        this.definitions = definitions;
        this.schemas = schemas;
    }

    /**
     * One resolved instance: the secret-free view plus the values a run needs and a view must not show.
     *
     * @param view the secret-free view
     * @param password the selected migration password
     * @param placeholders the effective placeholder values
     * @param lockRetryCount the effective Flyway lock retry count, or {@code null} for Flyway's default
     */
    record Instance(
            MigrationInstanceView view, String password, Map<String, String> placeholders, Integer lockRetryCount) {

        Instance {
            placeholders = Map.copyOf(placeholders);
        }

        @Override
        public String toString() {
            return "Instance[view=" + view + "]";
        }
    }

    /**
     * Resolves and validates the named instances.
     *
     * @return the resolved instances in execution order
     * @throws MigrationRunException with {@link Stage#CONFIGURATION} and no report when the
     *     configuration or the declarations are invalid
     */
    List<Instance> resolve() {
        FlywayMode root = config.mode();
        if (root == null) {
            throw invalid(null, "flyway.mode must not be null in named mode");
        }
        Map<String, FlywayInstanceConfig> overrides = overrides();
        Map<String, MigrationDefinition> declared = declarations();
        for (String name : overrides.keySet()) {
            if (!declared.containsKey(name)) {
                throw invalid(name, "the override matches no wired migration declaration");
            }
        }
        List<MigrationDefinition> ordered = order(declared);
        if (ordered.isEmpty() && root != FlywayMode.DISABLED) {
            throw invalid(
                    null, "named mode needs at least one wired migration declaration unless the root mode is DISABLED");
        }
        if ((config.user() == null) != (config.password() == null)) {
            throw invalid(null, "flyway.user and flyway.password must be set together or not at all");
        }
        checkLockRetryCount(null, config.lockRetryCount());

        List<Instance> instances = new ArrayList<>();
        for (MigrationDefinition definition : ordered) {
            instances.add(resolveInstance(definition, overrides.get(definition.name()), root));
        }
        checkHistoryIdentities(instances);
        checkClasspathNesting(instances);
        return List.copyOf(instances);
    }

    private static MigrationRunException invalid(String instance, String detail) {
        return new MigrationRunException(
                "Named migration configuration failed" + (instance == null ? "" : " for instance '" + instance + "'")
                        + ": " + detail,
                null,
                Stage.CONFIGURATION,
                null);
    }

    // --- Names and graph ---

    private Map<String, FlywayInstanceConfig> overrides() {
        Map<String, FlywayInstanceConfig> overrides = new LinkedHashMap<>();
        for (FlywayInstanceConfig override : config.instances()) {
            String name = override.name();
            checkName(name, "override");
            if (overrides.putIfAbsent(name, override) != null) {
                throw invalid(name, "the instance has more than one override");
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
                throw invalid(name, "more than one migration declaration uses the name");
            }
        }
        return declared;
    }

    private static void checkName(String name, String kind) {
        if (!NAME.matcher(name).matches()) {
            throw invalid(name, "the " + kind + " name must match [a-z][a-z0-9-]{0,31}");
        }
        if (LEGACY_NAME.equals(name)) {
            throw invalid(name, "the " + kind + " name is reserved");
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
                    throw invalid(name, "the declaration depends on itself");
                }
                if (!declared.containsKey(dependency)) {
                    throw invalid(name, "the declaration depends on an undeclared instance");
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
            throw invalid(null, "the declarations " + cyclic + " form a dependency cycle");
        }
        return ordered;
    }

    // --- One instance ---

    private Instance resolveInstance(MigrationDefinition definition, FlywayInstanceConfig override, FlywayMode root) {
        String name = definition.name();
        Optional<FlywayInstanceConfig> o = Optional.ofNullable(override);

        FlywayMode mode = o.map(FlywayInstanceConfig::mode).orElse(root);
        if (permissiveness(mode) > permissiveness(root)) {
            throw invalid(name, "the mode " + mode + " exceeds the root mode ceiling " + root);
        }

        String user;
        String password;
        if (override != null && (override.user() != null || override.password() != null)) {
            if (override.user() == null || override.password() == null) {
                throw invalid(name, "the instance sets only one of user and password; set both or neither");
            }
            user = override.user();
            password = override.password();
        } else if (config.user() != null) {
            user = config.user();
            password = config.password();
        } else {
            user = pool.user();
            password = pool.password();
        }

        List<String> managed =
                managedSchemas(name, o.map(FlywayInstanceConfig::schemas).orElse(null));

        List<String> locations = o.map(FlywayInstanceConfig::locations).orElse(definition.locations());
        if (locations == null || locations.isEmpty()) {
            throw invalid(name, "the instance has no migration locations");
        }
        if (locations.stream().anyMatch(location -> location == null || location.isBlank())) {
            throw invalid(name, "a location is blank");
        }

        String table = o.map(FlywayInstanceConfig::table).orElse(DEFAULT_TABLE_PREFIX + name.replace('-', '_'));
        if (table.isBlank()) {
            throw invalid(name, "the history table must not be blank");
        }

        Map<String, String> placeholders = o.map(FlywayInstanceConfig::placeholders)
                .orElse(config.placeholders() == null ? Map.of() : config.placeholders());
        if (placeholders.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
            throw invalid(name, "placeholders must not contain null names or values");
        }

        Integer lockRetryCount = o.map(FlywayInstanceConfig::lockRetryCount).orElse(config.lockRetryCount());
        checkLockRetryCount(name, lockRetryCount);

        MigrationInstanceView view = new MigrationInstanceView(
                name,
                managed.isEmpty() ? null : managed.get(0),
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
                user,
                placeholders.keySet());
        return new Instance(view, password, placeholders, lockRetryCount);
    }

    private static int permissiveness(FlywayMode mode) {
        return switch (mode) {
            case DISABLED -> 0;
            case VALIDATE -> 1;
            case MIGRATE -> 2;
        };
    }

    /** Rejects a lock retry count below {@code -1}; the message names the setting, never its value. */
    private static void checkLockRetryCount(String instance, Integer value) {
        if (value != null && value < -1) {
            throw invalid(
                    instance,
                    (instance == null ? "flyway.lockRetryCount" : "flyway.instances." + instance + ".lockRetryCount")
                            + " must be -1 or greater");
        }
    }

    /**
     * Resolves the effective managed schema list. With a {@code db.schemas} entry the entry comes first
     * and an explicit list must start with it; without one the explicit or inherited list is used as is.
     */
    private List<String> managedSchemas(String name, List<String> explicit) {
        List<String> source = explicit != null ? explicit : Objects.requireNonNullElse(config.schemas(), List.of());
        Set<String> seen = new HashSet<>();
        for (String schema : source) {
            if (schema == null || schema.isBlank()) {
                throw invalid(name, "the schemas list contains a blank entry");
            }
            if (!seen.add(schema)) {
                throw invalid(name, "the schemas list contains a duplicate entry");
            }
        }
        Optional<String> entry = schemas.schema(name);
        if (entry.isEmpty()) {
            return source;
        }
        String placement = entry.get();
        if (explicit != null) {
            if (explicit.isEmpty()) {
                throw invalid(name, "an explicit empty schemas list conflicts with the instance's db.schemas entry");
            }
            if (!explicit.get(0).equals(placement)) {
                throw invalid(
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

    // --- Across instances ---

    /** Rejects two instances that keep their history in the same table of the same (possibly unset) schema. */
    private static void checkHistoryIdentities(List<Instance> instances) {
        Map<List<String>, String> owners = new HashMap<>();
        for (Instance instance : instances) {
            MigrationInstanceView view = instance.view();
            String previous = owners.putIfAbsent(Arrays.asList(view.schema(), view.table()), view.name());
            if (previous != null) {
                throw invalid(
                        null,
                        "the instances '" + previous + "' and '" + view.name()
                                + "' use the same history table in the same schema");
            }
        }
    }

    /**
     * Rejects classpath locations of different instances that are equal or nested in one another: Flyway
     * cannot see across instances, and a parent directory would pick up the scripts of a child.
     */
    private static void checkClasspathNesting(List<Instance> instances) {
        for (int i = 0; i < instances.size(); i++) {
            for (int j = i + 1; j < instances.size(); j++) {
                for (String a : classpathPaths(instances.get(i))) {
                    for (String b : classpathPaths(instances.get(j))) {
                        if (nested(a, b) || nested(b, a)) {
                            throw invalid(
                                    null,
                                    "locations of the instances '"
                                            + instances.get(i).view().name() + "' and '"
                                            + instances.get(j).view().name() + "' overlap");
                        }
                    }
                }
            }
        }
    }

    private static List<String> classpathPaths(Instance instance) {
        return instance.view().locations().stream()
                .filter(location -> location.startsWith(CLASSPATH))
                .map(location -> location.substring(CLASSPATH.length()).replaceAll("^/+|/+$", ""))
                .toList();
    }

    private static boolean nested(String outer, String inner) {
        return inner.equals(outer) || inner.startsWith(outer + "/");
    }
}
