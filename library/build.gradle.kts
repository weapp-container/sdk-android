plugins { id("com.android.library"); kotlin("android"); `maven-publish`; id("org.jetbrains.dokka") }
group = "io.github.weapp-container"
version = "0.1.0-preview.7.1"
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("com.google.zxing:core:3.5.3") }
val nativeRoot = providers.gradleProperty("weappSdkNativeDir").map { rootProject.file(it) }
val licensesRoot = providers.gradleProperty("weappSdkLicensesDir").map { rootProject.file(it) }
val generatedJni = layout.buildDirectory.dir("generated/jniLibs")
val generatedNotices = layout.buildDirectory.dir("generated/notices")
android {
    namespace = "com.sonofmagic.weapp.container.sdk"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.1.12297006"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "com.sonofmagic.weapp.container.sdk.GlesRecoveryInstrumentation"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake {
            cppFlags += "-std=c++17"
            arguments += "-DWEAPP_SDK_NATIVE_DIR=${nativeRoot.get().absolutePath}"
            arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
        } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].jniLibs.srcDir(generatedJni)
    sourceSets["main"].assets.srcDir(generatedNotices)
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/recoveryAssets"))
    // These are already reviewed, hash-pinned runtime artifacts. Re-stripping
    // them would invalidate the identity proven by the runtime distribution.
    packaging { jniLibs.keepDebugSymbols += setOf("**/libweapp_container_sdk.so", "**/libc++_shared.so") }
    publishing { singleVariant("release") { withSourcesJar() } }
}
val copyNativeLibraries = tasks.register<Sync>("copyNativeLibraries") {
    for ((abi, target) in mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")) {
        from(nativeRoot.map { it.resolve(target) }) { include("libweapp_container_sdk.so", "libc++_shared.so"); into(abi) }
    }
    into(generatedJni)
    doFirst {
        for (target in listOf("aarch64-linux-android", "x86_64-linux-android")) {
            for (lib in listOf("libweapp_container_sdk.so", "libc++_shared.so")) {
                check(nativeRoot.get().resolve("$target/$lib").isFile) { "Supply the version-pinned private runtime artifact: $target/$lib" }
            }
        }
    }
}
val copySdkNotices = tasks.register<Sync>("copySdkNotices") {
    from(rootProject.file("LICENSE")) { into("weapp-container") }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("weapp-container/sdk-dependencies") }
    from(rootProject.file("licenses")) { into("weapp-container/sdk-dependencies/licenses") }
    for (target in listOf("aarch64-linux-android", "x86_64-linux-android")) {
        from(licensesRoot.map { it.resolve(target) }) { into("weapp-container/licenses/$target") }
    }
    from(nativeRoot.map { it.resolve("RUNTIME-LICENSE") }) { into("weapp-container") }
    into(generatedNotices)
    doFirst {
        for (target in listOf("aarch64-linux-android", "x86_64-linux-android")) {
            check(licensesRoot.get().resolve("$target/licenses.json").isFile) { "Supply verified private runtime dependency notices for $target before packaging" }
        }
    }
}
tasks.named("preBuild") { dependsOn(copyNativeLibraries, copySdkNotices) }
val sdkJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("dokkaHtml"))
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") {
            from(components["release"])
            artifactId = "weapp-container-android"
            artifact(sdkJavadocJar)
            pom {
                name.set("Weapp Container Android SDK")
                description.set("Embeddable native mini-program session and GLES view for Android")
                url.set("https://github.com/weapp-container/sdk-android")
                developers { developer { id.set("weapp-container"); name.set("Weapp Container contributors"); url.set("https://github.com/weapp-container") } }
                scm {
                    url.set("https://github.com/weapp-container/sdk-android")
                    connection.set("scm:git:https://github.com/weapp-container/sdk-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/weapp-container/sdk-android.git")
                }
                licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
            }
        } }
        repositories { maven { name = "localSdk"; url = uri(rootProject.file(providers.gradleProperty("weappSdkMavenDir").orElse("artifacts/android/maven").get())) } }
    }
}
