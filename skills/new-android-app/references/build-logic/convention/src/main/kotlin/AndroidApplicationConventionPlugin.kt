import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
            apply("org.jetbrains.kotlin.plugin.parcelize")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<BaseAppModuleExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 26
                targetSdk = 35
                vectorDrawables { useSupportLibrary = true }
            }
            compileOptions {
                sourceCompatibility = org.gradle.api.JavaVersion.VERSION_21
                targetCompatibility = org.gradle.api.JavaVersion.VERSION_21
            }
            buildFeatures { buildConfig = true }
            packaging {
                resources.excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE*",
                )
            }
            lint {
                // Bootstrap-template false positives:
                //  - Instantiatable: lint sometimes can't resolve ComponentActivity's
                //    inheritance through the AndroidX deps in a fresh project.
                //  - NewApi: lint flow analysis through `when` doesn't always pick up
                //    `Build.VERSION.SDK_INT >= S` guards on dynamic color calls.
                disable += setOf("Instantiatable", "NewApi")
                abortOnError = true
                checkReleaseBuilds = false
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(21)
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }

        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}
