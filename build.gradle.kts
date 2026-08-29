// Top-level build file. Configuration common to all modules goes here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// The project lives under OneDrive ("바탕 화면"), whose live sync intermittently locks files in
// build/ and makes Kotlin's incremental compiler fail with "Unable to delete directory".
// So build *intermediates* go to a non-synced local folder, but the finished debug APK is
// copied back into the project folder (AI\260829_diving\apk\) with a human name.
val syncSafeRoot = (System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"))
    .trimEnd('/', '\\')

// rootProject dir = ...\AI\260829_diving\DivingReplay  →  parent = ...\AI\260829_diving
val apkExportDir = rootProject.projectDir.parentFile.resolve("apk")

// Bump alongside Constants.VERSION_LABEL / app_name / module versionName.
val appVersionLabel = "v2"

subprojects {
    layout.buildDirectory.set(file("$syncSafeRoot/DivingReplayBuild/${rootProject.name}/${project.name}"))

    val prettyApkName =
        if (project.name == "wear") "Replay Video YS $appVersionLabel (watch).apk"
        else "Replay Video YS $appVersionLabel.apk"

    val exportDebugApk = tasks.register<Copy>("exportDebugApk") {
        from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
        into(apkExportDir)
        rename { prettyApkName }
        doLast { logger.lifecycle("APK -> ${apkExportDir.resolve(prettyApkName)}") }
    }

    tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(exportDebugApk) }
}
