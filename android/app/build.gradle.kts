plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hammam.attendai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hammam.attendai"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("int", "DATABASE_VERSION", "5")
    }

    signingConfigs {
        val ksPath=System.getenv("ANDROID_KEYSTORE_PATH")
        val ksPassword=System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val keyAliasValue=System.getenv("ANDROID_KEY_ALIAS")
        val keyPasswordValue=System.getenv("ANDROID_KEY_PASSWORD")
        if(!ksPath.isNullOrBlank()&&!ksPassword.isNullOrBlank()&&!keyAliasValue.isNullOrBlank()&&!keyPasswordValue.isNullOrBlank()){
            create("release"){
                storeFile=file(ksPath);storePassword=ksPassword;keyAlias=keyAliasValue;keyPassword=keyPasswordValue
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled=false
            signingConfigs.findByName("release")?.let{signingConfig=it}
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging.resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}


ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
