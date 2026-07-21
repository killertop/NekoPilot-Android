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

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("nb4a.properties").inputStream())
        }
    }
    return metadata
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
        defaultConfig {
            minSdk = 21
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
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
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
                    ).replace("-release", "").replace("-oss", "")
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
            }
        }
    }

    val verifyOfficialReleaseReadiness = tasks.register("verifyOfficialReleaseReadiness") {
        group = "verification"
        description = "Checks production signing and device regression approval."
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
        val isReleasePackage = (name.startsWith("package") || name.startsWith("bundle")) &&
                name.endsWith("Release")
        if (isReleasePackage) {
            dependsOn(verifyOfficialReleaseReadiness)
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
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
