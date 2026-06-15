plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.0.1"
}
