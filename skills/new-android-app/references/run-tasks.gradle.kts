// Registers runDebug / runRelease / runDebugAll / runReleaseAll tasks.
// Each assembles + installs + launches the main activity via adb.

import org.gradle.api.tasks.Exec

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

fun launchOn(serial: String, applicationId: String): Exec.() -> Unit = {
    commandLine(
        "adb", "-s", serial, "shell", "am", "start", "-n",
        "$applicationId/.MainActivity",
    )
}

listOf("Debug", "Release").forEach { variant ->
    val installTask = "install$variant"
    val singleName = "run${variant}"
    val allName = "run${variant}All"

    tasks.register(singleName) {
        group = "run"
        description = "Assemble, install, and launch the $variant variant on the single connected device."
        dependsOn(installTask)
        doLast {
            val devices = adbDevices()
            check(devices.isNotEmpty()) { "No connected devices. Connect one or run :${allName}." }
            check(devices.size == 1) {
                "${devices.size} devices connected: ${devices.joinToString()}. Use :$allName or disconnect all but one."
            }
            val applicationId = (android as com.android.build.gradle.internal.dsl.BaseAppModuleExtension)
                .defaultConfig.applicationId!! +
                if (variant == "Debug") ".debug" else ""
            exec(launchOn(devices.single(), applicationId))
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
            val applicationId = (android as com.android.build.gradle.internal.dsl.BaseAppModuleExtension)
                .defaultConfig.applicationId!! +
                if (variant == "Debug") ".debug" else ""
            devices.forEach { serial ->
                exec(launchOn(serial, applicationId))
                logger.lifecycle("Launched $applicationId on $serial")
            }
        }
    }
}
