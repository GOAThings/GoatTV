// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 traz Kotlin embutido; declarar o plugin aqui só fixa a versão do Kotlin usada.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
