def failsafeReports = new File(basedir, "project/minimal-app/target/failsafe-reports")

assert failsafeReports.isDirectory()
assert failsafeReports
        .listFiles()
        .any { it.name.startsWith("TEST-") && it.name.endsWith("ApplicationIT.xml") }
