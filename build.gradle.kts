// Version numbers here are the single most likely thing to need a bump -
// Android Studio will usually offer to auto-upgrade AGP/Gradle on first
// open if it wants a newer combination; accepting that upgrade is normal
// and expected, not a sign anything here is wrong.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // NOTE: KSP's version string must match the Kotlin version above
    // exactly (format "<kotlin-version>-<ksp-revision>") - if Android
    // Studio complains this pairing doesn't exist, check
    // https://github.com/google/ksp/releases for the revision that goes
    // with whatever Kotlin version you end up on.
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}

// Броня со встроенным разрешением экспериментальных фич для всего проекта
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(false)
            // Автоматически разрешаем использовать экспериментальные Material 3 API без ошибок
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
            )
        }
    }
}
