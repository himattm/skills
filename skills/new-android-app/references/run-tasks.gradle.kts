// Registers runDebug / runRelease / runDebugAll / runReleaseAll tasks.
// Each assembles + installs + launches the main activity via adb.
// Applied via `apply(from = rootProject.file("gradle/run-tasks.gradle.kts"))`
// from the consuming module's build.gradle.kts (must be an Android application module).
//
// We avoid importing AGP types here so this file can be applied without the
// android-gradle-plugin being on the script's compile classpath. We resolve
// the application id reflectively at task-execution time.

import org.gradle.api.Project

fun adbDevices(): List<String> {
    val proc = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
    proc.waitFor()
    return proc.inputStream.bufferedReader().readLines()
        .drop(1)
        .mapNotNull { line ->
            val cols = line.trim().split("\\s+".toRegex())
            if (cols.size >= 2 && cols[1] == "device") cols[0] else null
        }
}

fun resolveApplicationId(project: Project, variant: String): String {
    // BaseAppModuleExtension is registered under the name "android" by AGP.
    val androidExt = project.extensions.findByName("android")
        ?: error("No 'android' extension on ${project.path}; is com.android.application applied?")
    val defaultConfig = androidExt::class.java.getMethod("getDefaultConfig").invoke(androidExt)
    val base = defaultConfig::class.java.getMethod("getApplicationId").invoke(defaultConfig) as? String
        ?: error("applicationId is not set on android.defaultConfig in ${project.path}")
    return base + if (variant == "Debug") ".debug" else ""
}

fun launchOn(serial: String, applicationId: String) {
    val proc = ProcessBuilder(
        "adb", "-s", serial, "shell", "am", "start", "-n",
        "$applicationId/.MainActivity",
    ).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    val exit = proc.waitFor()
    check(exit == 0) { "adb am start failed (exit=$exit) for $serial:\n$output" }
}

listOf("Debug", "Release").forEach { variant ->
    val installTask = "install$variant"
    val singleName = "run$variant"
    val allName = "run${variant}All"
    val proj = project

    tasks.register(singleName) {
        group = "run"
        description = "Assemble, install, and launch the $variant variant on the single connected device."
        dependsOn(installTask)
        doLast {
            val devices = adbDevices()
            check(devices.isNotEmpty()) { "No connected devices. Connect one or run :$allName." }
            check(devices.size == 1) {
                "${devices.size} devices connected: ${devices.joinToString()}. Use :$allName or disconnect all but one."
            }
            val applicationId = resolveApplicationId(proj, variant)
            launchOn(devices.single(), applicationId)
            logger.lifecycle("Launched $applicationId on ${devices.single()}")
        }
    }

    tasks.register(allName) {
        group = "run"
        description = "Assemble, install, and launch the $variant variant on every connected device."
        dependsOn(installTask)
        doLast {
            val devices = adbDevices()
            check(devices.isNotEmpty()) { "No connected devices." }
            val applicationId = resolveApplicationId(proj, variant)
            devices.forEach { serial ->
                launchOn(serial, applicationId)
                logger.lifecycle("Launched $applicationId on $serial")
            }
        }
    }
}
