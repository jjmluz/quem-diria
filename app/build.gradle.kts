plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the music into the app's assets, when it's there: the room's (`raquel.ogg` at the project root) as
 * `music/title.ogg`, and the game's (`background_*.m4a`) under their own names in `music/`. The tracks aren't
 * ours to share, so they're kept out of git; without them the app is quiet.
 */
abstract class CopyMusic : DefaultTask() {
    @get:InputFiles
    abstract val music: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        music.files.filter { it.isFile }.forEach {
            val name = if (it.name == "raquel.ogg") "title.ogg" else it.name
            it.copyTo(out.resolve("music/$name"), overwrite = true)
        }
    }
}

val copyMusic = tasks.register<CopyMusic>("copyMusic") {
    music.from(rootProject.file("raquel.ogg"), rootProject.fileTree(rootProject.projectDir) {
        include("background_*.m4a")
    })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyMusic, CopyMusic::outputDir)
    }
}

android {
    namespace = "com.jjmluz.quemdiria"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jjmluz.quemdiria"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded personal build: sign with the debug key so `installRelease` just works.
            signingConfig = signingConfigs.getByName("debug")
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
