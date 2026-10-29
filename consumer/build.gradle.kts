plugins { id("com.android.application") version "8.7.3"; kotlin("android") version "2.0.21" }
android {
    namespace = "com.sonofmagic.weapp.container.consumer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.sonofmagic.weapp.container.sdkconsumer"
        minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0"
        testInstrumentationRunner = "com.sonofmagic.weapp.container.consumer.SdkInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = true; signingConfig = signingConfigs.getByName("debug"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "consumer-test.pro") } }
    testBuildType = "release"
    packaging { jniLibs.keepDebugSymbols += setOf("**/libweapp_container_sdk.so", "**/libc++_shared.so") }
}
dependencies { implementation("com.sonofmagic.weapp:weapp-container-android:0.1.0-preview.5") }
