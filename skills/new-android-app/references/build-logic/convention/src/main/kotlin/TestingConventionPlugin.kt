import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class TestingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.github.takahirom.roborazzi")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<BaseAppModuleExtension> {
            testOptions {
                unitTests {
                    isIncludeAndroidResources = true
                    isReturnDefaultValues = true
                }
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit-jupiter-api").get())
            add("testImplementation", libs.findLibrary("junit-jupiter-params").get())
            add("testRuntimeOnly", libs.findLibrary("junit-jupiter-engine").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("kotest-assertions").get())
            add("testImplementation", libs.findLibrary("robolectric").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("roborazzi").get())
            add("testImplementation", libs.findLibrary("roborazzi-compose").get())
            add("testImplementation", libs.findLibrary("roborazzi-junit-rule").get())
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        }
    }
}
