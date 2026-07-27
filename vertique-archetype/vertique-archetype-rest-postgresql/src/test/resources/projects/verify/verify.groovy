// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// --- Expected report shape ---
// Named bindings rather than inline literals: this hook is the seam a future shared archetype
// verifier parameterizes, so the report identity, its case count, and its case names are the three
// values a sibling archetype would rebind.

String expectedReportPath =
        "project/rest-postgresql-app/target/failsafe-reports/TEST-com.example.restpostgresqlapp.ApplicationIT.xml"
String expectedTestCount = "2"
Set<String> expectedTestNames = ["supportsItemCrud", "bootstrapReadsWorkingDirectoryConfig"] as Set

// --- Test proof: the generated application's integration suite ran exactly those cases, all green ---
//
// The generated ApplicationIT drives a real Testcontainers PostgreSQL instance, so this report is
// only produced by a Docker-backed run: without a reachable daemon the container start throws and
// the nested `mvn verify` fails before any report exists. The skipped=0 assertion is the no-escape
// proof — the templates carry no assumption, disable annotation, or profile that could turn an
// absent daemon into a silently green, skipped case.

File failsafeReport = new File(basedir, expectedReportPath)
assert failsafeReport.isFile(): "Missing failsafe report ${expectedReportPath}"

def testSuite = new XmlSlurper(false, false).parse(failsafeReport)
assert testSuite.@tests.text() == expectedTestCount:
        "ApplicationIT must run exactly ${expectedTestCount} tests, ran ${testSuite.@tests.text()}"
assert testSuite.@failures.text() == "0": "ApplicationIT reported failures"
assert testSuite.@errors.text() == "0": "ApplicationIT reported errors"
assert testSuite.@skipped.text() == "0": "ApplicationIT skipped tests"

Set<String> actualTestNames = testSuite.testcase.collect { it.@name.text() }.toSet()
assert actualTestNames == expectedTestNames:
        "ApplicationIT case set drifted from the frozen set: " +
                "unexpected=${actualTestNames - expectedTestNames}, missing=${expectedTestNames - actualTestNames}"

return true
