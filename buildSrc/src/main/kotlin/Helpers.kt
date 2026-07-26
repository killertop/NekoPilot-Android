import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

private lateinit var localProperties: Properties

private val supportedVersionName = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")
private val supportedNdkVersion = Regex("^\\d+\\.\\d+\\.\\d+$")

private fun Project.requireAndroidNdkVersion(): String {
    val versionFile = rootProject.layout.projectDirectory.file(".android-ndk-version")
    require(versionFile.asFile.isFile) {
        "Missing Android NDK version file: ${versionFile.asFile.absolutePath}"
    }
    val raw = rootProject.providers.fileContents(versionFile).asText.get()
    val value = raw.trim()
    require(value.isNotEmpty()) {
        ".android-ndk-version must not be empty"
    }
    require(supportedNdkVersion.matches(value)) {
        ".android-ndk-version must contain one numeric major.minor.patch value"
    }
    require(raw == "$value\n") {
        ".android-ndk-version must contain exactly one LF-terminated line"
    }
    return value
}

private fun validateVersionName(raw: String): String {
    val value = raw.trim()
    val match = supportedVersionName.matchEntire(value)
        ?: error("VERSION_NAME must use exactly three numeric components: major.minor.patch")
    val patch = match.groupValues[3].toIntOrNull()
        ?: error("VERSION_NAME patch component must be numeric")
    require(patch in 0..10) {
        "VERSION_NAME patch component must be between 0 and 10: $value"
    }
    return value
}

fun Project.requireMetadata(): Properties = Properties().apply {
    rootProject.file("nb4a.properties").inputStream().use { input ->
        load(input)
    }
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
        // Local developer settings provide safe defaults. CI/Codex can then supply
        // LOCAL_PROPERTIES as an explicit overlay without hiding local signing data.
        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {
            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "35.0.1"
        compileSdk = 35
        ndkVersion = requireAndroidNdkVersion()
        defaultConfig {
            // Official sing-box libbox 1.14 uses Android APIs introduced in API 23.
            // Keep the application floor aligned with the native runtime instead of
            // overriding its manifest, which would only fail on Android 5.x at runtime.
            minSdk = 23
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        project.extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions.jvmTarget
            .set(JvmTarget.JVM_1_8)
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
            applicationVariants.forEach { variant ->
                variant.outputs.forEach {
                    it as BaseVariantOutputImpl
                    it.outputFileName = it.outputFileName.replace(
                        "app", "${project.name}-" + variant.versionName
                    ).replace("-release", "")
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val keystoreFile = lp.getProperty("KEYSTORE_FILE") ?: System.getenv("KEYSTORE_FILE")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")
    val keystoreExists = !keystoreFile.isNullOrBlank() && rootProject.file(keystoreFile).isFile
    val releaseSigningConfigured = !keystorePwd.isNullOrBlank() && keystoreExists &&
            !alias.isNullOrBlank() && !pwd.isNullOrBlank()
    val deviceRegressionConfirmed =
        (lp.getProperty("DEVICE_REGRESSION_CONFIRMED")
            ?: System.getenv("DEVICE_REGRESSION_CONFIRMED")).toBoolean()

    android.apply {
        if (releaseSigningConfigured) {
            signingConfigs {
                create("release") {
                    storeFile = rootProject.file(requireNotNull(keystoreFile))
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
        }
        buildTypes {
            val key = signingConfigs.findByName("release")
            if (key != null) {
                getByName("release").signingConfig = key
            }
            create("qa") {
                initWith(getByName("release"))
                applicationIdSuffix = ".qa"
                versionNameSuffix = "-qa"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += listOf("release")
                // initWith does not reliably carry every optimization flag across AGP versions.
                // QA is the production-like regression variant, so set both knobs explicitly.
                isMinifyEnabled = true
                isShrinkResources = true
            }
        }
    }

    val verifyOptimizedDistributionBuildTypes = tasks.register("verifyOptimizedDistributionBuildTypes") {
        group = "verification"
        description = "Ensures QA and release packages always use R8 and resource shrinking."
        doLast {
            check(System.getenv("nkmr_minify") != "0") {
                "nkmr_minify=0 is forbidden for QA and release builds. Use the debug variant instead."
            }
            listOf("qa", "release").forEach { buildTypeName ->
                val buildType = android.buildTypes.getByName(buildTypeName)
                check(buildType.isMinifyEnabled) {
                    "$buildTypeName must enable R8 minification."
                }
                check(buildType.isShrinkResources) {
                    "$buildTypeName must enable resource shrinking."
                }
            }
        }
    }

    val verifyOfficialReleaseReadiness = tasks.register("verifyOfficialReleaseReadiness") {
        group = "verification"
        description = "Checks production signing and device regression approval."
        dependsOn(verifyOptimizedDistributionBuildTypes)
        doLast {
            check(releaseSigningConfigured) {
                "Official release packaging requires a valid KEYSTORE_FILE, KEYSTORE_PASS, " +
                        "ALIAS_NAME and ALIAS_PASS. Use a QA variant for local testing."
            }
            check(deviceRegressionConfirmed) {
                "Official release packaging requires DEVICE_REGRESSION_CONFIRMED=true after " +
                        "the device regression checklist has passed."
            }
        }
    }
    tasks.configureEach {
        val isPackage = name.startsWith("package") || name.startsWith("bundle")
        when {
            isPackage && name.endsWith("Release") -> {
                dependsOn(verifyOfficialReleaseReadiness)
            }
            isPackage && name.endsWith("Qa") -> {
                dependsOn(verifyOptimizedDistributionBuildTypes)
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = validateVersionName(requireMetadata().getProperty("VERSION_NAME"))
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    // CI overrides this only for its x86_64 emulator; local and release builds stay arm64.
    val requestedAbi = providers.gradleProperty("nekopilot.abi")
        .orElse("arm64-v8a")
        .get()
        .trim()
    val supportedAbis = setOf("arm64-v8a", "x86_64")
    require(requestedAbi in supportedAbis) {
        "nekopilot.abi must be one of: ${supportedAbis.sorted().joinToString()}"
    }
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
            // `qa` is created from `release` before this block runs. Copying the
            // build type does not pick up proguard files that are added later,
            // which previously allowed R8 to rename Gson-backed profile fields.
            getByName("qa") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
                // Instrumentation APKs apply the target QA mapping. Keep their runtime bridge
                // rules QA-only so release remains fully optimized without test-only retention.
                proguardFile(file("proguard-qa-rules.pro"))
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include(requestedAbi)
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                outputFileName = outputFileName.replace(project.name, "NekoPilot-$versionName")
                    .replace("-release", "")
                    .replace("-qa.apk", ".apk")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}
