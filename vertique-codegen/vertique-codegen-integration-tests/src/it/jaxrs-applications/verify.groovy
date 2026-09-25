// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// T003 TP-005 and TP-006: proves the nested reactor build succeeded by reading each nested module's
// own surefire report. A missing report — for example, `app`'s report when the baseline processor
// never writes `app`'s own generated module and the unit fails to compile — fails this hook, which is
// exactly how a skipped nested suite (or a nested module that never reaches `test`) fails the fixture.

/**
 * Locates and validates one nested module's surefire report, asserting exactly one test ran with no
 * failure, error, or skip, and prints the counts read from it.
 *
 * @param moduleDir     the nested module directory name (relative to {@code basedir})
 * @param testClassName the simple name of the {@code @Test} class whose report is expected
 * @return the report's tests/failures/errors/skipped counts, as a map, for logging
 */
def assertNestedReport(String moduleDir, String testClassName) {
    File reportsDir = new File(basedir, "${moduleDir}/target/surefire-reports")
    assert reportsDir.isDirectory():
            "Missing surefire-reports directory for '${moduleDir}' — the nested module never reached its test phase: ${reportsDir}"

    File report = reportsDir.listFiles()?.find { it.name.startsWith("TEST-") && it.name.endsWith("${testClassName}.xml") }
    assert report != null:
            "Missing surefire report for ${testClassName} in '${moduleDir}': the nested test never ran " +
                    "(reports present: ${reportsDir.listFiles()?.collect { it.name }})"

    def testSuite = new XmlSlurper(false, false).parse(report)
    String tests = testSuite.@tests.text()
    String failures = testSuite.@failures.text()
    String errors = testSuite.@errors.text()
    String skipped = testSuite.@skipped.text()

    assert tests == "1": "${testClassName} ('${moduleDir}') must run exactly one test, ran ${tests}"
    assert failures == "0": "${testClassName} ('${moduleDir}') reported failures: ${failures}"
    assert errors == "0": "${testClassName} ('${moduleDir}') reported errors: ${errors}"
    assert skipped == "0": "${testClassName} ('${moduleDir}') skipped tests: ${skipped}"

    println "jaxrs-applications: ${moduleDir}/${testClassName} — tests=${tests} failures=${failures} errors=${errors} skipped=${skipped}"
    return [tests: tests, failures: failures, errors: errors, skipped: skipped]
}

// `legacy` (TP-006) must always pass: it declares no application, so it never depends on the
// application-registration emission this task adds.
assertNestedReport("legacy", "ZeroApplicationLegacyTest")

// `app` (TP-005) proves the end-to-end generated shape; at the baseline processor its report is
// missing entirely because the module never compiles (see AppComponent's Javadoc).
assertNestedReport("app", "TwoUnitApplicationsTest")

return true
