import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The name a person sees lives in project.json at the repository root; nothing in the app spells it out.
val appName: String = providers.fileContents(rootProject.layout.projectDirectory.file("../project.json"))
    .asText
    .map { (JsonSlurper().parseText(it) as Map<*, *>)["appName"] as? String ?: "" }
    .get()
require(appName.isNotEmpty() && appName == appName.trim() && appName.length <= 60 && appName.none { it == '\n' || it == '\r' }) {
    "project.json: appName must be one line of 1 to 60 characters with no outer spaces"
}

android {
    // Permanent: Android recognises the installed app, its queue and its settings by this id.
    namespace = "io.github.leoalfaroc.journal"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.leoalfaroc.journal"
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
    }

    buildFeatures {
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
