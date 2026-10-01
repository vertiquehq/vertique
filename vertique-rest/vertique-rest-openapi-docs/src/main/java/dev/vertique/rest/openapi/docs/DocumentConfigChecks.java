// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The configuration checks of the documentation module, run by the selection of enabled documents
 * once {@code apidocs.enabled} has resolved to {@code true}.
 *
 * <p>The checks run in three steps. First, every key of the raw {@code apidocs.documents} object,
 * in sorted order and whatever the entry's {@code enabled} value, must follow the application-name
 * grammar, name a declared application (active or not), hold only {@code enabled}, {@code info},
 * and {@code serverUrl}, hold no {@code enabled} string the parser cannot map to a boolean (a blank string or
 * one made only of control characters parses to no value, so it fails instead of keeping the
 * annotation's decision), and set {@code enabled: true}
 * only for an application whose declaring interface carries {@link ApiDocs}; the first violation
 * fails. Second, the {@link ApiDocs} of every
 * active application is re-checked for its shape, whether or not configuration disables its
 * document, and every violation is reported in one failure. Third, only when at least one document
 * is enabled, {@code apidocs.path} is checked, then the {@code info} and {@code serverUrl} of each
 * enabled document in name order; the first violation fails.
 *
 * <p>Every failure that concerns a declared application names the application and its declaring
 * interface. No failure echoes a configuration value or an annotation attribute value; an entry key
 * appears only inside its configuration path, with each control character rendered as a
 * Java-style Unicode escape of four hexadecimal digits and nothing truncated.
 */
final class DocumentConfigChecks {

    /** The application-name grammar a document entry's key must match. */
    private static final String NAME_GRAMMAR = "[a-z0-9][a-z0-9_-]{0,63}";

    private static final Pattern NAME = Pattern.compile(NAME_GRAMMAR);

    /** The keys a document entry may hold. */
    private static final Set<String> SUPPORTED_KEYS = Set.of("enabled", "info", "serverUrl");

    private DocumentConfigChecks() {}

    /**
     * Runs every configuration check and resolves the {@code info} of each selected document.
     *
     * @param config the root configuration, whose raw {@code apidocs.documents} keys are checked
     * @param apidocsConfig the parsed {@code apidocs} section, which is enabled
     * @param applications the declared applications of the component
     * @param selected the documents the configuration and the annotations enable, in name order
     * @return the selected documents, each carrying its resolved {@code info}
     * @throws ConfigurationException when a document entry, {@code apidocs.path}, or the
     *     {@code info} or {@code serverUrl} of an enabled document is invalid
     * @throws RestConfigurationException when the {@link ApiDocs} of an active application is
     *     malformed
     */
    static List<EnabledDocuments.EnabledDocument> check(
            JsonObject config,
            ApidocsConfig apidocsConfig,
            RestApplications applications,
            List<EnabledDocuments.EnabledDocument> selected) {
        checkEntries(config, apidocsConfig, applications);
        checkApiDocsShapes(applications);
        if (selected.isEmpty()) {
            return List.of();
        }
        checkPath(apidocsConfig.path());
        List<EnabledDocuments.EnabledDocument> resolved = new ArrayList<>(selected.size());
        for (EnabledDocuments.EnabledDocument document : selected) {
            AnnotatedInfo annotated = resolveInfo(document);
            InfoConfig info = annotated == null
                    ? document.info()
                    : new InfoConfig(annotated.title(), annotated.version(), annotated.description());
            checkServerUrl(document);
            resolved.add(new EnabledDocuments.EnabledDocument(
                    document.name(),
                    document.declaringType(),
                    document.access(),
                    document.mountPath(),
                    document.contractOrigin(),
                    info,
                    document.serverUrl(),
                    annotated));
        }
        return List.copyOf(resolved);
    }

    // ---- entries ----

    private static void checkEntries(JsonObject config, ApidocsConfig apidocsConfig, RestApplications applications) {
        JsonObject section = JsonConfigPaths.navigateObject(config, "apidocs");
        if (!(section.getValue("documents") instanceof JsonObject documents)) {
            return;
        }
        Map<String, DocumentConfig> parsed = apidocsConfig.documents().stream()
                .collect(Collectors.toMap(DocumentConfig::name, Function.identity(), (first, second) -> first));
        for (String key : new TreeSet<>(documents.fieldNames())) {
            String path = "apidocs.documents." + escape(key);
            if (!NAME.matcher(key).matches()) {
                throw new ConfigurationException("Invalid configuration '" + path
                        + "': a document name is its application's name and must match " + NAME_GRAMMAR
                        + ", so no application can be declared under this name");
            }
            RestApplications.Entry application = applications
                    .byName(key)
                    .orElseThrow(() -> new ConfigurationException(
                            "Invalid configuration '" + path + "': no application of that name is declared"));
            DocumentConfig document = parsed.get(key);
            if (documents.getValue(key) instanceof JsonObject entry) {
                checkEntryKeys(application, path, entry);
                if (entry.getValue("enabled") instanceof String && (document == null || document.enabled() == null)) {
                    throw new ConfigurationException(describe(application) + " has an invalid '" + path
                            + ".enabled': it must be true or false, or be left out to keep the @ApiDocs decision");
                }
            }
            if (document != null
                    && Boolean.TRUE.equals(document.enabled())
                    && application.declaringType().getAnnotation(ApiDocs.class) == null) {
                throw new ConfigurationException(describe(application) + " sets '" + path
                        + ".enabled', but the interface declares no @ApiDocs; that setting can only switch a "
                        + "documented application off");
            }
        }
    }

    private static void checkEntryKeys(RestApplications.Entry application, String path, JsonObject entry) {
        Set<String> unknown = new TreeSet<>();
        for (String key : entry.fieldNames()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String paths = unknown.stream()
                .map(key -> "'" + path + "." + escape(key) + "'")
                .collect(Collectors.joining(", "));
        throw new ConfigurationException(describe(application) + " has unsupported keys " + paths
                + ": an entry supports only 'enabled', 'info', and 'serverUrl'; access is declared by @ApiDocs in "
                + "code");
    }

    // ---- @ApiDocs shape ----

    private static void checkApiDocsShapes(RestApplications applications) {
        List<String> violations = new ArrayList<>();
        for (RestApplications.Entry application : applications.all()) {
            if (!application.active()) {
                continue;
            }
            ApiDocs annotation = application.declaringType().getAnnotation(ApiDocs.class);
            if (annotation == null) {
                continue;
            }
            String prefix = describe(application) + ": ";
            boolean isProtected = annotation.access() == ApiDocs.Access.PROTECTED;
            boolean blankScheme = annotation.securityScheme().isBlank();
            if (isProtected && blankScheme) {
                violations.add(prefix + "@ApiDocs.securityScheme must name a scheme when access is PROTECTED");
            }
            if (!isProtected && !blankScheme) {
                violations.add(prefix + "@ApiDocs.securityScheme must be empty when access is PUBLIC");
            }
            String[] roles = annotation.rolesAllowed();
            if (!isProtected && roles.length > 0) {
                violations.add(prefix + "@ApiDocs.rolesAllowed must be empty when access is PUBLIC");
            }
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    violations.add(prefix + "@ApiDocs.rolesAllowed entries must not be blank");
                    break;
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new RestConfigurationException("Invalid @ApiDocs declarations:\n  "
                    + String.join("\n  ", violations.stream().sorted().toList()));
        }
    }

    // ---- enabled documents ----

    private static void checkPath(String path) {
        boolean valid = path != null
                && path.startsWith("/")
                && path.length() > 1
                && !path.endsWith("/")
                && !path.contains("//")
                && path.chars().noneMatch(c -> c == '*' || c == ':' || c == '{' || c == '}' || c == '?' || c == '#')
                && path.chars().noneMatch(Character::isWhitespace);
        if (valid) {
            for (String segment : path.substring(1).split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw new ConfigurationException("Invalid configuration 'apidocs.path': the prefix must be a literal "
                    + "path that starts with '/', is not '/', has no trailing '/', contains none of "
                    + "'*', ':', '{', '}', '?', '#', whitespace or '//', and has no '.' or '..' segment");
        }
    }

    /**
     * Resolves the {@code info} of an enabled document: the configured one when present, which
     * replaces the annotation as a whole, else the complete {@code info} of an {@link
     * OpenAPIDefinition} carried by the declaring interface itself (never by a superinterface).
     *
     * @return {@code null} when the configured {@code info} is valid, else the annotated one
     * @throws ConfigurationException when neither source supplies a non-blank {@code title} and
     *     {@code version}, or when the annotated license sets both {@code identifier} and {@code url}
     */
    @Nullable
    private static AnnotatedInfo resolveInfo(EnabledDocuments.EnabledDocument document) {
        String base = "apidocs.documents." + document.name() + ".info";
        InfoConfig configured = document.info();
        if (configured != null) {
            if (configured.title() == null || configured.title().isBlank()) {
                throw infoFailure(document, base + ".title");
            }
            if (configured.version() == null || configured.version().isBlank()) {
                throw infoFailure(document, base + ".version");
            }
            return null;
        }
        OpenAPIDefinition definition = document.declaringType().getDeclaredAnnotation(OpenAPIDefinition.class);
        if (definition == null) {
            throw infoFailure(document, base);
        }
        Info info = definition.info();
        if (info.title().isBlank() || info.version().isBlank()) {
            throw new ConfigurationException("Application '" + document.name() + "' (declared by "
                    + document.declaringType().getName()
                    + ") has a blank title or version in @OpenAPIDefinition.info; set them there or configure '"
                    + base + "'");
        }
        if (AnnotatedInfo.hasIdentifierAndUrl(info.license())) {
            throw new ConfigurationException("Application '" + document.name() + "' (declared by "
                    + document.declaringType().getName()
                    + ") sets both identifier and url in @OpenAPIDefinition.info.license, which OpenAPI 3.1"
                    + " makes mutually exclusive; keep one of them or configure '" + base + "'");
        }
        return AnnotatedInfo.of(info);
    }

    private static ConfigurationException infoFailure(EnabledDocuments.EnabledDocument document, String settingPath) {
        return new ConfigurationException("Application '" + document.name() + "' (declared by "
                + document.declaringType().getName() + ") needs a non-blank configured '" + settingPath + "'");
    }

    private static void checkServerUrl(EnabledDocuments.EnabledDocument document) {
        String serverUrl = document.serverUrl();
        if (serverUrl == null || isValidServerUrl(serverUrl)) {
            return;
        }
        throw new ConfigurationException("Application '" + document.name() + "' (declared by "
                + document.declaringType().getName() + ") has an invalid 'apidocs.documents." + document.name()
                + ".serverUrl': it must be an absolute http or https URI with a host, or an absolute path "
                + "starting with a single '/'");
    }

    /**
     * Reports whether a server URL is an absolute {@code http} or {@code https} URI with a non-empty
     * host, the scheme compared case-insensitively, or an absolute path that starts with exactly one
     * {@code /} and has neither scheme nor authority.
     */
    private static boolean isValidServerUrl(String serverUrl) {
        URI uri;
        try {
            uri = new URI(serverUrl);
        } catch (URISyntaxException e) {
            return false;
        }
        if (serverUrl.startsWith("/")) {
            return !serverUrl.startsWith("//") && uri.getScheme() == null && uri.getRawAuthority() == null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && host != null
                && !host.isEmpty();
    }

    // ---- messages ----

    /** Names an application, quoted, and its declaring interface's binary name. */
    private static String describe(RestApplications.Entry application) {
        return "Application '" + application.name() + "' (declared by "
                + application.declaringType().getName() + ")";
    }

    /**
     * Renders every control character of a configuration key as a Java-style Unicode escape of four
     * uppercase hexadecimal digits, leaving every other character unchanged.
     */
    private static String escape(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isISOControl(c)) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
