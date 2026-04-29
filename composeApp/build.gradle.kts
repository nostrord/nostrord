import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Suppress expect/actual classes beta warnings
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser {
            // Configure webpack for production builds
            webpackTask {
                mainOutputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Configure webpack for production builds
            webpackTask {
                mainOutputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation("io.ktor:ktor-client-okhttp:3.0.0")
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.14.0")
            implementation("androidx.security:security-crypto:1.1.0")
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            // Animated GIF support: provides AnimatedImageDecoder (API 28+) and GifDecoder (API < 28)
            implementation("io.coil-kt.coil3:coil-gif:3.0.4")
            // Media3 ExoPlayer for video playback (same stack as Amethyst)
            implementation("androidx.media3:media3-exoplayer:1.6.0")
            implementation("androidx.media3:media3-ui:1.6.0")
        }
        
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            
            implementation("io.ktor:ktor-client-core:3.0.0")
            implementation("io.ktor:ktor-client-websockets:3.0.0")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
            implementation(compose.materialIconsExtended)
            implementation("io.github.alexzhirkevich:qrose:1.0.1")
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
        }
        
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("media.kamel:kamel-image:0.9.5")
            implementation("io.ktor:ktor-client-cio:3.0.0")
            implementation("fr.acinq.secp256k1:secp256k1-kmp:0.14.0")
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.14.0")
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            implementation("org.slf4j:slf4j-nop:2.0.9")
            // Inline video player (GStreamer on Linux, MediaFoundation on Win, AVPlayer on Mac)
            implementation("io.github.kdroidfilter:composemediaplayer:0.8.3")
            // JavaFX WebView for YouTube iframe on desktop
            val fxVersion = "17.0.14"
            val os = org.gradle.internal.os.OperatingSystem.current()
            val fxClassifier = when {
                os.isLinux -> "linux"
                os.isMacOsX -> "mac"
                os.isWindows -> "win"
                else -> "linux"
            }
            implementation("org.openjfx:javafx-base:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-graphics:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-controls:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-web:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-swing:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-media:$fxVersion:$fxClassifier")
            // Pure-Java MP3 decoder for notification chime — avoids the GStreamer
            // native-library matrix that JavaFX Media relies on.
            implementation("javazoom:jlayer:1.0.1")
        }
        
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.0.0")
            implementation("media.kamel:kamel-image:0.9.5")
            // Compression webpack plugin for production builds
            implementation(devNpm("compression-webpack-plugin", "11.1.0"))
        }

        val wasmJsMain by getting {
            dependencies {
                // Ktor JS engine for HTTP requests (required for Coil image loading)
                implementation("io.ktor:ktor-client-js:3.0.0")
                // Compression webpack plugin for production builds
                implementation(devNpm("compression-webpack-plugin", "11.1.0"))
            }
        }
    }
}

// Release signing — reads from keystore.properties at the repo root (gitignored).
// Devs without the file can still build debug; release builds require it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "org.nostr.nostrord"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.nostr.nostrord"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile").trim())
                storePassword = keystoreProps.getProperty("storePassword").trim()
                keyAlias = keystoreProps.getProperty("keyAlias").trim()
                keyPassword = keystoreProps.getProperty("keyPassword").trim()
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.nostr.nostrord.MainKt"

        jvmArgs(
            "-Dsun.java2d.wmClassName=Nostrord",
            // JavaFX module access for WebView/MediaPlayer when loaded from classpath
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Nostrord"
            packageVersion = "1.0.0"

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon-512.png"))
                debPackageVersion = packageVersion
                appCategory = "Network"
            }

            macOS {
                iconFile.set(project.file("src/jvmMain/resources/icon-512.png"))
                bundleID = "org.nostr.nostrord"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Nostrord Deep Link</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>nostrord</string>
                                </array>
                            </dict>
                        </array>
                    """
                }
            }

            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon-512.png"))
                menuGroup = "Nostrord"
            }
        }

        buildTypes.release {
            proguard {
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }
}

// Post-process .deb to install icons into hicolor theme and fix .desktop metadata.
// jpackage does NOT do this — it dumps a single PNG into /opt/<app>/lib/ and references
// it by absolute path, which GNOME on Debian does not reliably resolve.
tasks.register("fixDebPackage") {
    description = "Repack .deb with hicolor icons and correct .desktop metadata"
    dependsOn("packageDeb")

    // Resolve paths at configuration time (configuration-cache safe)
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val repackDir = layout.buildDirectory.dir("deb-repack")
    val iconSrcDir = layout.projectDirectory.dir("src/jvmMain/resources/linux-icons")

    doLast {
        fun run(vararg args: String) {
            val proc = ProcessBuilder(*args).inheritIO().start()
            check(proc.waitFor() == 0) { "Command failed: ${args.joinToString(" ")}" }
        }

        val debFile = debDir.get().asFile.listFiles()!!.first { it.extension == "deb" }
        val workDir = repackDir.get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()

        val extractDir = File(workDir, "extract")
        val controlDir = File(workDir, "control")
        extractDir.mkdirs()
        controlDir.mkdirs()

        // Extract .deb
        run("dpkg-deb", "-x", debFile.absolutePath, extractDir.absolutePath)
        run("dpkg-deb", "-e", debFile.absolutePath, controlDir.absolutePath)

        // Install icons into hicolor
        val sizes = listOf(16, 32, 48, 64, 128, 256, 512)
        for (s in sizes) {
            val hicolorDir = File(extractDir, "usr/share/icons/hicolor/${s}x${s}/apps")
            hicolorDir.mkdirs()
            val src = File(iconSrcDir.asFile, "icon-${s}.png")
            if (src.exists()) {
                src.copyTo(File(hicolorDir, "nostrord.png"), overwrite = true)
            }
        }

        // Patch .desktop file: use icon theme name, add StartupWMClass and proper Categories
        // walkTopDown enumerates dirs too — the bundled JDK runtime contains a
        // legal/java.desktop directory that would match without the isFile filter.
        val desktopFile = extractDir.walkTopDown().first { it.isFile && it.extension == "desktop" }
        // On Wayland, Java AWT derives app_id from the main class name:
        // org.nostr.nostrord.MainKt → org-nostr-nostrord-MainKt
        // StartupWMClass MUST match this for GNOME to associate the window.
        // On X11, sun.java2d.wmClassName=Nostrord overrides WM_CLASS,
        // so we list both as a fallback chain via StartupWMClass matching the Wayland value.
        val patchedDesktop = """
            |[Desktop Entry]
            |Name=Nostrord
            |Comment=Nostrord - NOSTR NIP-29 Client
            |Exec=/opt/nostrord/bin/Nostrord %u
            |Icon=nostrord
            |Terminal=false
            |Type=Application
            |Categories=Network;InstantMessaging;Chat;
            |StartupWMClass=org-nostr-nostrord-MainKt
            |MimeType=x-scheme-handler/nostrord;
        """.trimMargin() + "\n"
        desktopFile.writeText(patchedDesktop)

        // Patch postinst to run gtk-update-icon-cache after install
        val postinst = File(controlDir, "postinst")
        val postinstText = postinst.readText()
        val patchedPostinst = postinstText.replace(
            "xdg-desktop-menu install /opt/nostrord/lib/nostrord-Nostrord.desktop",
            "xdg-desktop-menu install /opt/nostrord/lib/nostrord-Nostrord.desktop\n" +
            "    xdg-mime default nostrord-Nostrord.desktop x-scheme-handler/nostrord 2>/dev/null || true\n" +
            "    xdg-icon-resource forceupdate --theme hicolor 2>/dev/null || true\n" +
            "    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true"
        )
        postinst.writeText(patchedPostinst)

        // Patch prerm to clean up hicolor icons on uninstall
        val prerm = File(controlDir, "prerm")
        val prermText = prerm.readText()
        val d = '$'
        val cleanupScript = "xdg-desktop-menu uninstall /opt/nostrord/lib/nostrord-Nostrord.desktop\n" +
            "    for s in 16 32 48 64 128 256 512; do\n" +
            "        rm -f \"/usr/share/icons/hicolor/${d}{s}x${d}{s}/apps/nostrord.png\"\n" +
            "    done\n" +
            "    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true"
        val patchedPrerm = prermText.replace(
            "xdg-desktop-menu uninstall /opt/nostrord/lib/nostrord-Nostrord.desktop",
            cleanupScript
        )
        prerm.writeText(patchedPrerm)

        // Repack .deb — copy control files into DEBIAN dir and set correct permissions
        val debianDir = File(extractDir, "DEBIAN")
        debianDir.mkdirs()
        controlDir.listFiles()?.forEach { file ->
            val dest = File(debianDir, file.name)
            file.copyTo(dest, overwrite = true)
            // Maintainer scripts must be executable (dpkg-deb requires >=0555)
            if (file.name in listOf("postinst", "prerm", "postrm", "preinst")) {
                dest.setExecutable(true, false)
            }
        }

        run("dpkg-deb", "--build", "--root-owner-group", extractDir.absolutePath, debFile.absolutePath)

        println("Repacked .deb with hicolor icons: ${debFile.name}")
    }
}

// Stamp build version into index.html and sw.js for cache busting
abstract class StampBuildVersionTask : DefaultTask() {
    @get:InputDirectory
    abstract val distDir: DirectoryProperty

    @TaskAction
    fun stamp() {
        val buildVersion = System.currentTimeMillis().toString()
        val dir = distDir.get().asFile
        listOf("index.html", "sw.js").forEach { filename ->
            val file = File(dir, filename)
            if (file.exists()) {
                file.writeText(file.readText().replace("__BUILD_VERSION__", buildVersion))
            }
        }
        println("Stamped build version $buildVersion into web assets")
    }
}

tasks.register<StampBuildVersionTask>("stampWasmJsBuildVersion") {
    dependsOn("wasmJsBrowserProductionWebpack")
    mustRunAfter("copyWasmJsCompressedFiles")
    distDir.set(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
}

tasks.register<StampBuildVersionTask>("stampJsBuildVersion") {
    dependsOn("jsBrowserProductionWebpack")
    mustRunAfter("copyJsCompressedFiles")
    distDir.set(layout.buildDirectory.dir("dist/js/productionExecutable"))
}

// Copy pre-compressed files (.gz, .br) to distribution folder for wasmJs
tasks.register<Copy>("copyWasmJsCompressedFiles") {
    dependsOn("wasmJsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/wasmJs/productionExecutable")) {
        include("**/*.gz", "**/*.br")
    }
    into(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
}

// Copy pre-compressed files for js target
tasks.register<Copy>("copyJsCompressedFiles") {
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
        include("**/*.gz", "**/*.br")
    }
    into(layout.buildDirectory.dir("dist/js/productionExecutable"))
}

// Make distribution tasks depend on copy and stamp tasks
tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach {
    finalizedBy("copyWasmJsCompressedFiles", "stampWasmJsBuildVersion")
}

tasks.matching { it.name == "jsBrowserDistribution" }.configureEach {
    finalizedBy("copyJsCompressedFiles", "stampJsBuildVersion")
}
