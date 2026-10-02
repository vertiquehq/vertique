// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Checks the example's documentation page and what answers under the documentation prefix.
 *
 * <p>The page must load only the pinned Redoc script from jsDelivr, checked by its integrity value,
 * under a restrictive content security policy, and read only the public document. Under the prefix,
 * only the enabled documents and the page answer. No test request leaves the loopback interface.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ApiDocsUiIT {

    private static final String UI_PAGE = "/apidocs/ui/";

    /** The pinned Redoc standalone bundle, by exact version. */
    private static final String EXPECTED_REDOC_URL =
            "https://cdn.jsdelivr.net/npm/redoc@2.5.4/bundles/redoc.standalone.js";

    /** The integrity value of {@link #EXPECTED_REDOC_URL}. */
    private static final String EXPECTED_REDOC_INTEGRITY =
            "sha384-w447zOpYfw/1Tv/5AK9NfHTlQIqE3RVR6KY62jCyy9zNDgO64cMwGGP1Fj0zJVf5";

    /** The restrictive base policy, as {@code "<directive> <source>"} entries. */
    private static final Set<String> BASE_POLICY = Set.of(
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "connect-src 'self'",
            "frame-ancestors 'none'");

    /** The entry that allows exactly the pinned script. */
    private static final Set<String> PINNED_SCRIPT_ENTRY = Set.of("script-src " + EXPECTED_REDOC_URL);

    /**
     * The additions Redoc needs outside {@code script-src}, as {@code "<directive> <source>"}
     * entries, as measured in a browser loading the page.
     */
    private static final Set<String> REDOC_ADDITIONS = Set.of("style-src 'unsafe-inline'", "worker-src blob:");

    private static final Pattern EXACT_JSDELIVR_VERSION = Pattern.compile(
            "https://cdn\\.jsdelivr\\.net/npm/redoc@\\d+\\.\\d+\\.\\d+/bundles/redoc\\.standalone\\.js");
    private static final Pattern SCRIPT_ELEMENT =
            Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern EVENT_HANDLER_ATTRIBUTE =
            Pattern.compile("<[a-zA-Z][^>]*\\son[a-zA-Z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_ATTRIBUTE = Pattern.compile(
            "\\s(?:src|href|action|data|poster|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /** Text neither the page nor its first-party scripts may contain. */
    private static final List<String> FORBIDDEN_PAGE_TEXT =
            List.of("/apidocs/management/", "localStorage", "sessionStorage", "Authorization");

    /** Each URL under the prefix with the status an anonymous {@code GET} must receive. */
    private static final Map<String, Integer> PREFIX_ANSWERS = prefixAnswers();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(TestConfiguration.forTest());

    /** Configures RestAssured base URI, port, and logging filters. */
    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
    }

    /** Resets RestAssured configuration after all tests complete. */
    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    @DisplayName("the page loads the pinned Redoc script under the restrictive policy")
    void uiPageLoadsPinnedRedocUnderRestrictivePolicy() {
        // Given: the pinned script the example declares
        assertEquals(EXPECTED_REDOC_URL, ApiDocsUiMount.REDOC_SCRIPT_URL, "the example's pinned Redoc URL");
        assertEquals(
                EXPECTED_REDOC_INTEGRITY, ApiDocsUiMount.REDOC_SCRIPT_INTEGRITY, "the example's Redoc integrity value");

        // When: the page is requested
        Response page = given().when().get(UI_PAGE);

        // Then: it is HTML under exactly the expected policy
        assertEquals(200, page.statusCode(), "GET " + UI_PAGE + "; body: " + page.asString());
        assertEquals(
                "text/html; charset=utf-8",
                String.valueOf(page.header("Content-Type")).toLowerCase(Locale.ROOT),
                "Content-Type");
        Set<String> policy = policyEntries(page.header("Content-Security-Policy"));
        Set<String> expectedPolicy = new HashSet<>(BASE_POLICY);
        expectedPolicy.addAll(PINNED_SCRIPT_ENTRY);
        expectedPolicy.addAll(REDOC_ADDITIONS);
        assertEquals(expectedPolicy, policy, "Content-Security-Policy entries");
        assertEquals(
                Set.of("script-src 'self'", "script-src " + EXPECTED_REDOC_URL),
                withDirective(policy, "script-src"),
                "script-src must hold exactly 'self' and the pinned URL");
        assertPolicyHasNoLooseSource(policy);

        // Then: the only external reference is the pinned, integrity-checked script
        String html = page.asString();
        List<Map<String, String>> scripts = scripts(html);
        List<Map<String, String>> external = scripts.stream()
                .filter(script -> isExternal(script.getOrDefault("src", "")))
                .toList();
        assertEquals(1, external.size(), "exactly one external script: " + scripts);
        Map<String, String> redoc = external.get(0);
        assertAll(
                () -> assertEquals(EXPECTED_REDOC_URL, redoc.get("src"), "the external script's src"),
                () -> assertTrue(
                        EXACT_JSDELIVR_VERSION.matcher(redoc.get("src")).matches(),
                        "the src must pin an exact major.minor.patch version"),
                () -> assertEquals(EXPECTED_REDOC_INTEGRITY, redoc.get("integrity"), "integrity"),
                () -> assertEquals("anonymous", redoc.get("crossorigin"), "crossorigin"),
                () -> assertEquals(
                        List.of(EXPECTED_REDOC_URL),
                        externalUrls(html),
                        "the page must reference no other external URL"),
                () -> assertTrue(
                        scripts.stream()
                                .allMatch(script -> script.get("#content").isBlank()),
                        "no script element may have content"),
                () -> assertFalse(
                        EVENT_HANDLER_ATTRIBUTE.matcher(html).find(),
                        "no element may have an event-handler attribute"));

        // When: every first-party script is requested
        StringBuilder firstParty = new StringBuilder(html);
        for (Map<String, String> script : scripts) {
            String src = script.get("src");
            if (src == null || isExternal(src)) {
                continue;
            }
            assertTrue(src.startsWith("/apidocs/ui/"), "a first-party script must live under /apidocs/ui/: " + src);
            Response body = given().when().get(src);
            assertEquals(200, body.statusCode(), "GET " + src);
            assertTrue(
                    String.valueOf(body.header("Content-Type")).contains("javascript"),
                    "Content-Type of " + src + ": " + body.header("Content-Type"));
            firstParty.append('\n').append(body.asString());
        }

        // Then: the page reads only the public document and keeps no credential
        String content = firstParty.toString();
        assertTrue(content.contains("/apidocs/public/openapi.json"), "the page must load the public document");
        assertEquals(
                List.of(),
                FORBIDDEN_PAGE_TEXT.stream().filter(content::contains).toList(),
                "text the page and its first-party scripts must not contain");
    }

    @Test
    @DisplayName(
            "under the prefix only the document URLs and the page answer, and no preview OpenAPI library is present")
    void docsModuleAnswersOnlyDocumentUrls() {
        // Given: the started example
        // When: each URL under the prefix is requested anonymously
        List<String> mismatches = new ArrayList<>();
        PREFIX_ANSWERS.forEach((path, expected) -> {
            int actual = given().urlEncodingEnabled(false).when().get(path).statusCode();
            if (actual != expected) {
                mismatches.add(path + " answered " + actual + ", expected " + expected);
            }
        });

        // Then: each answers its expected status
        assertEquals(List.of(), mismatches, "answers under /apidocs");

        // When: the page is requested with HEAD
        Response head = given().when().head(UI_PAGE);

        // Then: it answers 200 like GET, with no body
        assertEquals(200, head.statusCode(), "HEAD " + UI_PAGE + " must answer 200 like GET");
        assertEquals(0, head.asByteArray().length, "HEAD " + UI_PAGE + " must answer with no body");

        // Then: neither preview OpenAPI routing library is on the class path
        ClassLoader loader = ApiDocsUiIT.class.getClassLoader();
        assertAll(
                () -> assertThrows(
                        ClassNotFoundException.class,
                        () -> Class.forName("io.vertx.openapi.contract.OpenAPIContract", false, loader),
                        "io.vertx:vertx-openapi must not be on the class path"),
                () -> assertThrows(
                        ClassNotFoundException.class,
                        () -> Class.forName("io.vertx.ext.web.openapi.router.RouterBuilder", false, loader),
                        "io.vertx:vertx-web-openapi-router must not be on the class path"));
    }

    private static Map<String, Integer> prefixAnswers() {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("/apidocs/public/openapi.json", 200);
        answers.put("/apidocs/public/openapi.yaml", 200);
        answers.put("/apidocs/management/openapi.json", 401);
        answers.put("/apidocs/management/openapi.yaml", 401);
        answers.put(UI_PAGE, 200);
        answers.put("/apidocs", 404);
        answers.put("/apidocs/", 404);
        answers.put("/apidocs/index.html", 404);
        answers.put("/apidocs/public", 404);
        answers.put("/apidocs/public/", 404);
        answers.put("/apidocs/public/index.html", 404);
        answers.put("/apidocs/public/openapi", 404);
        answers.put("/apidocs/public/openapi.json/", 404);
        answers.put("/apidocs/management/", 404);
        answers.put("/apidocs/swagger-ui/index.html", 404);
        answers.put("/apidocs/unknown/openapi.json", 404);
        return answers;
    }

    /**
     * Splits a policy into {@code "<directive> <source>"} entries; a directive without sources is an
     * entry of its own name.
     *
     * @param header the header value, or {@code null}
     * @return the entries, empty when the header is absent
     */
    private static Set<String> policyEntries(String header) {
        Set<String> entries = new HashSet<>();
        if (header == null) {
            return entries;
        }
        for (String directive : header.split(";")) {
            String[] parts = directive.trim().split("\\s+");
            if (parts[0].isEmpty()) {
                continue;
            }
            String name = parts[0].toLowerCase(Locale.ROOT);
            if (parts.length == 1) {
                entries.add(name);
            }
            for (int i = 1; i < parts.length; i++) {
                entries.add(name + " " + parts[i]);
            }
        }
        return entries;
    }

    private static Set<String> withDirective(Set<String> policy, String directive) {
        Set<String> entries = new HashSet<>();
        for (String entry : policy) {
            if (entry.equals(directive) || entry.startsWith(directive + " ")) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Asserts that no policy source is the bare CDN origin, a path prefix, a wildcard, an unsafe
     * keyword, or a host or URL source other than the pinned script in {@code script-src}. Keywords
     * such as {@code 'self'} and scheme sources such as {@code data:} are not origins.
     *
     * @param policy the policy entries
     */
    private static void assertPolicyHasNoLooseSource(Set<String> policy) {
        List<String> loose = new ArrayList<>();
        for (String entry : policy) {
            int space = entry.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String directive = entry.substring(0, space);
            String source = entry.substring(space + 1);
            boolean pinnedScript = directive.equals("script-src") && source.equals(EXPECTED_REDOC_URL);
            boolean keyword = source.startsWith("'");
            boolean schemeOnly = source.matches("[a-z][a-z0-9+.-]*:");
            boolean external = !keyword && !schemeOnly;
            if (source.equals("https://cdn.jsdelivr.net")
                    || source.endsWith("/")
                    || source.contains("*")
                    || source.equals("'unsafe-eval'")
                    || (directive.equals("script-src") && source.equals("'unsafe-inline'"))
                    || (external && !pinnedScript)) {
                loose.add(entry);
            }
        }
        assertEquals(List.of(), loose, "policy entries that loosen the policy");
    }

    /**
     * Lists the page's script elements as attribute maps; {@code #content} holds the element's text.
     *
     * @param html the page
     * @return one map per script element, in document order
     */
    private static List<Map<String, String>> scripts(String html) {
        List<Map<String, String>> scripts = new ArrayList<>();
        Matcher element = SCRIPT_ELEMENT.matcher(html);
        while (element.find()) {
            Map<String, String> attributes = new LinkedHashMap<>();
            Matcher attribute = ATTRIBUTE.matcher(element.group(1));
            while (attribute.find()) {
                String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
                attributes.put(attribute.group(1).toLowerCase(Locale.ROOT), value);
            }
            attributes.put("#content", element.group(2));
            scripts.add(attributes);
        }
        return scripts;
    }

    /**
     * Lists every URL-valued attribute of the page that points to another origin.
     *
     * @param html the page
     * @return the external URLs, in document order
     */
    private static List<String> externalUrls(String html) {
        List<String> urls = new ArrayList<>();
        Matcher attribute = URL_ATTRIBUTE.matcher(html);
        while (attribute.find()) {
            String value = attribute.group(1) != null ? attribute.group(1) : attribute.group(2);
            if (isExternal(value)) {
                urls.add(value);
            }
        }
        return urls;
    }

    private static boolean isExternal(String url) {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("//") || lower.matches("^[a-z][a-z0-9+.-]*:.*");
    }
}
