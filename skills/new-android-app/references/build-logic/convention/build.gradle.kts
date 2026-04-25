plugins {
    `kotlin-dsl`
}

group = "{{packageName}}.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "{{packageName}}.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("compose") {
            id = "{{packageName}}.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("circuit") {
            id = "{{packageName}}.circuit"
            implementationClass = "CircuitConventionPlugin"
        }
        register("metro") {
            id = "{{packageName}}.metro"
            implementationClass = "MetroConventionPlugin"
        }
        register("testing") {
            id = "{{packageName}}.testing"
            implementationClass = "TestingConventionPlugin"
        }
        register("quality") {
            id = "{{packageName}}.quality"
            implementationClass = "QualityConventionPlugin"
        }
    }
}
