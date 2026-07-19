@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

val rustCoreDirectory = rootProject.layout.projectDirectory.dir("rust/nekodata-core")
val rustAndroidJniDirectory = layout.buildDirectory.dir("generated/rustJni")
val rustHostJniDirectory = layout.buildDirectory.dir("generated/rustHostJni")
val buildRustDataCoreAndroid by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds the Rust data core for the packaged arm64-v8a APK."
    inputs.dir(rustCoreDirectory)
    inputs.file(rootProject.file("scripts/build-rust-data-core.sh"))
    outputs.dir(rustAndroidJniDirectory)
    commandLine(
        "bash",
        rootProject.file("scripts/build-rust-data-core.sh").absolutePath,
        rustAndroidJniDirectory.get().dir("arm64-v8a").asFile.absolutePath,
    )
}
val buildRustDataCoreHost by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds the Rust data core used by local JVM tests."
    inputs.dir(rustCoreDirectory)
    inputs.file(rootProject.file("scripts/build-rust-data-core-host.sh"))
    outputs.dir(rustHostJniDirectory)
    commandLine(
        "bash",
        rootProject.file("scripts/build-rust-data-core-host.sh").absolutePath,
        rustHostJniDirectory.get().asFile.absolutePath,
    )
}

setupApp()

val bundledRuleAssets = listOf(
    "geoip.db.xz",
    "geoip.version.txt",
    "geosite.db.xz",
    "geosite.version.txt",
)
val ruleAssetsDirectory = layout.projectDirectory.dir("src/main/assets/sing-box")
val prepareRuleAssets by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Downloads the bundled geo rule assets required by the default China rules."
    val assetFiles = bundledRuleAssets.map(ruleAssetsDirectory::file)
    inputs.file(rootProject.file("buildScript/lib/assets.sh"))
    outputs.files(assetFiles)
    onlyIf { assetFiles.any { !it.asFile.isFile } }
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("buildScript/lib/assets.sh").absolutePath)
}

tasks.named("preBuild") {
    dependsOn(prepareRuleAssets)
    dependsOn(buildRustDataCoreAndroid)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        // Review dependency upgrades separately; availability alone is not a correctness failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "zh-rCN")
    }
    sourceSets {
        getByName("main").jniLibs.srcDir(rustAndroidJniDirectory)
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(buildRustDataCoreHost)
    inputs.file(rustHostJniDirectory.map { it.file("libnekodata_core.dylib") })
    jvmArgs("-Djava.library.path=${rustHostJniDirectory.get().asFile.absolutePath}")
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("com.squareup.okio:okio:3.17.0")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
