plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    // KSP for Room's annotation processor. Version is pinned to the Kotlin version (1.9.25).
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
