import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "com.pixelpy.editor"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pixelpy.editor"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
        testInstrumentationRunner = "com.pixelpy.editor.PixelPyTestRunner"
    }
    flavorDimensions += "target"
    productFlavors {
        create("development") {
            dimension = "target"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("production") {
            dimension = "target"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        managedDevices {
            localDevices {
                create("pixelApi35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp"
                }
            }
        }
    }
}
androidComponents {
    beforeVariants(selector().all()) { variant ->
        val target = variant.productFlavors
            .firstOrNull { it.first == "target" }
            ?.second
        variant.enable =
            (target == "development" && variant.buildType == "debug") ||
            (target == "production" && variant.buildType == "release")
    }
}
chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("C:/Users/x13/AppData/Local/Programs/Python/Python313/python.exe")
        pip {
            install("requests==2.34.2")
            install("beautifulsoup4==4.15.0")
            install("openpyxl==3.1.5")
            install("defusedxml==0.7.1")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs unit tests for the emulator-capable debug variant."
    dependsOn("testDevelopmentDebugUnitTest")
}
tasks.register("lintDebug") {
    group = "verification"
    description = "Runs lint for the emulator-capable debug variant."
    dependsOn("lintDevelopmentDebug")
}
tasks.register("pixelApi35DebugAndroidTest") {
    group = "verification"
    description = "Runs debug instrumentation tests on pixelApi35."
    dependsOn("pixelApi35DevelopmentDebugAndroidTest")
}
