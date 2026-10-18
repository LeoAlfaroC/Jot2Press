import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The names a person sees live in project.json at the repository root; nothing in the app spells them out.
fun declaredName(key: String): String {
    val name = providers.fileContents(rootProject.layout.projectDirectory.file("../project.json"))
        .asText
        .map { (JsonSlurper().parseText(it) as Map<*, *>)[key] as? String ?: "" }
        .get()
    require(name.isNotEmpty() && name == name.trim() && name.length <= 60 && name.none { it == '\n' || it == '\r' }) {
        "project.json: $key must be one line of 1 to 60 characters with no outer spaces"
    }
    return name
}

val appName: String = declaredName("appName")
val pluginName: String = declaredName("pluginName")

// Release builds are signed with a key kept outside the repository, named in ~/.gradle/gradle.properties.
// Losing the key means the installed app can only be replaced by uninstalling it, queue and settings included.
val releaseKey: Map<String, String?> = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS")
    .associate { "JOT2PRESS_RELEASE_$it" to providers.gradleProperty("JOT2PRESS_RELEASE_$it").orNull }

android {
    // Permanent: Android recognises the installed app, its queue and its settings by this id.
    namespace = "io.github.leoalfaroc.jot2press"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.leoalfaroc.jot2press"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An Android string resource: a backslash, a quote, or a leading @ or ? has to be escaped.
        resValue(
            "string",
            "app_name",
            appName.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace(Regex("^[@?]"), "\\\\$0"),
        )
        // A Java string literal: a backslash or a quote has to be escaped.
        buildConfigField("String", "PLUGIN_NAME", "\"" + pluginName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }

    signingConfigs {
        create("release") {
            if (releaseKey.values.all { it != null }) {
                storeFile = file(releaseKey.getValue("JOT2PRESS_RELEASE_STORE_FILE")!!)
                storePassword = releaseKey.getValue("JOT2PRESS_RELEASE_STORE_PASSWORD")
                keyAlias = releaseKey.getValue("JOT2PRESS_RELEASE_KEY_ALIAS")
                keyPassword = releaseKey.getValue("JOT2PRESS_RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric reaches into the JDK; from JDK 24 on that has to be allowed explicitly.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
            }
        }
    }
}

// Without the key a release build would come out unsigned; stop it with the names that are missing instead.
val missingReleaseKey: List<String> = releaseKey.filterValues { it == null }.keys.toList()
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val missing = missingReleaseKey
    doFirst {
        if (missing.isNotEmpty()) {
            throw GradleException("Release builds need ${missing.joinToString()} in ~/.gradle/gradle.properties; see android/README.md")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
