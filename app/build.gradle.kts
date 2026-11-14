plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the family's own files, kept out of git, into the app's assets under [folder] when they're there:
 * the music (`music/`: `lobby.*` for the room, `background_*` for the game, `winner.*` for its end) and the photo rules
 * (`private/photo-rules.json`: photos kept out of the game). Without them the app is quiet, with every photo allowed.
 */
abstract class CopyIntoAssets : DefaultTask() {
    @get:InputFiles
    abstract val files: ConfigurableFileCollection

    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        files.files.filter { it.isFile }.forEach { it.copyTo(out.resolve("${folder.get()}/${it.name}"), overwrite = true) }
    }
}

val copyMusic = tasks.register<CopyIntoAssets>("copyMusic") {
    files.from(rootProject.fileTree("music") { include("lobby.*", "background_*", "winner.*") })
    folder.set("music")
}

val copyPhotoRules = tasks.register<CopyIntoAssets>("copyPhotoRules") {
    files.from(rootProject.fileTree("private") { include("photo-rules.json") })
    folder.set("private")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyMusic, CopyIntoAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyPhotoRules, CopyIntoAssets::outputDir)
    }
}

android {
    namespace = "com.jjmluz.quemdiria"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jjmluz.quemdiria"
        minSdk = 26
        targetSdk = 37
        // CI passes these for releases (-PversionCode=… -PversionName=…); local builds use the defaults.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")
    }

    // CI signs releases with the keystore in the SIGNING_* secrets. Without them (local builds),
    // release falls back to the debug key, which is fine for sideloading but can't update a copy from GitHub.
    val releaseKeystore = providers.environmentVariable("SIGNING_KEYSTORE").orNull
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Tells Android which languages the game has, so it can offer them in the app's language setting.
        generateLocaleConfig = true
        // Leaves out the libraries' other translations, which the game's own text doesn't have.
        localeFilters += listOf("en", "pt")
        // Stored as is, so the music can be played straight from the APK.
        noCompress += "ogg"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.google.zxing:core:3.5.3")
}
