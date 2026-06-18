plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
}

// Pin ktlint engine to avoid transitive upgrades changing the rule set unexpectedly.
val ktlintVersion = libs.versions.ktlint.get()

// Rules disabled because they conflict with Compose / KMP conventions or with
// intentional codebase patterns. .editorconfig mirrors these for IDE awareness,
// but spotless reads this map directly to avoid editorconfig-discovery issues.
val ktlintDisabledRules = mapOf(
    "ktlint_standard_function-naming" to "disabled",
    "ktlint_standard_filename" to "disabled",
    "ktlint_standard_no-wildcard-imports" to "disabled",
    "ktlint_standard_backing-property-naming" to "disabled",
    "ktlint_standard_comment-wrapping" to "disabled",
    "ktlint_standard_max-line-length" to "disabled",
    "ktlint_standard_property-naming" to "disabled",
    "ktlint_standard_if-else-wrapping" to "disabled",
    "ktlint_standard_value-parameter-comment" to "disabled",
    "ktlint_standard_no-consecutive-comments" to "disabled",
)

spotless {
    kotlin {
        target("composeApp/src/**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(ktlintVersion).editorConfigOverride(ktlintDisabledRules)
    }
    kotlinGradle {
        target("*.gradle.kts", "composeApp/*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintDisabledRules)
    }
}
