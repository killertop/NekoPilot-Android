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

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {

            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
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
    val playPolicyConfirmed =
        (lp.getProperty("PLAY_POLICY_CONFIRMED")
            ?: System.getenv("PLAY_POLICY_CONFIRMED")).toBoolean()

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
    val verifyPlayReleaseReadiness = tasks.register("verifyPlayReleaseReadiness") {
        group = "verification"
        description = "Checks production and Google Play release approvals."
        dependsOn(verifyOfficialReleaseReadiness)
        doLast {
            check(playPolicyConfirmed) {
                "Google Play packaging requires PLAY_POLICY_CONFIRMED=true after the Play " +
                        "Console declarations and disclosure checklist are complete."
            }
        }
    }

    tasks.configureEach {
        val isReleasePackage = (name.startsWith("package") || name.startsWith("bundle")) &&
                name.endsWith("Release")
        if (isReleasePackage) {
            dependsOn(
                if (name.contains("PlayRelease")) verifyPlayReleaseReadiness
                else verifyOfficialReleaseReadiness
            )
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
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
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include("armeabi-v7a")
            include("arm64-v8a")
            include("x86")
            include("x86_64")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("play")
            create("preview") {
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\""
                )
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val isPreview = outputFileName.contains("-preview")
                outputFileName = if (isPreview) {
                    outputFileName.replace(
                        project.name,
                        "NekoPilot-" + requireMetadata().getProperty("PRE_VERSION_NAME")
                    ).replace("-preview", "")
                } else {
                    outputFileName.replace(project.name, "NekoPilot-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                        .replace("-qa.apk", ".apk")
                }
            }
        }

        for (abi in listOf("Arm64", "Arm", "X64", "X86")) {
            tasks.create("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}
