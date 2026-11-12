import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// O núcleo do GoatTV partilhado entre o Android (jvm) e a Apple TV / iPhone (GoatShared.xcframework): a lógica pura,
// escrita uma vez. As plataformas só põem o que é delas (ver os expect/actual).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // O mesmo bytecode que a app (Java 17), com o JDK que corre o Gradle (sem procurar outros JDKs).
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Só compila num Mac (o CI da Apple); no Linux estes alvos ficam de fora sozinhos.
    val xcf = XCFramework("GoatShared")
    listOf(tvosArm64(), tvosSimulatorArm64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "GoatShared"
            isStatic = true
            xcf.add(this)
        }
    }
}
