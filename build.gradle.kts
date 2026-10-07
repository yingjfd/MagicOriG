// Top-level build script
plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
