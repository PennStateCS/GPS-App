// Shared configuration for the geokit libraries: JaCoCo line/branch coverage for every Kotlin/JVM module.
//
//   ./gradlew -p libs test jacocoTestReport
//
// Reports: libs/<module>/build/reports/jacoco/test/html/index.html (and jacocoTestReport.xml).
// `test` does not depend on the report, so a plain `./gradlew -p libs test` costs nothing extra.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
    }
}
