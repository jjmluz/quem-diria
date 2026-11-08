plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the title music (`raquel.ogg` at the project root) into the app's assets as `music/title.ogg`,
 * when it's there. The track isn't ours to share, so it's kept out of git; without it the room is quiet.
 */
abstract class CopyTitleMusic : DefaultTask() {
    @get:InputFiles
    abstract val music: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        music.files.filter { it.isFile }.forEach { it.copyTo(out.resolve("music/title.ogg"), overwrite = true) }
    }
}

val copyTitleMusic = tasks.register<CopyTitleMusic>("copyTitleMusic") {
    music.from(rootProject.file("raquel.ogg"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyTitleMusic, CopyTitleMusic::outputDir)
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
