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

    // Só compila num Mac (o CI da Apple); no Linux estes alvos ficam de fora sozinhos. O macOS é para os testes do
    // GoatCore (`swift test` no Mac do CI), que agora usa este núcleo.
    // GOATSHARED_SLICES (no CI) escolhe as fatias a compilar ("tvos,macos"…): cada uma leva minutos, e cada CI só
    // precisa das suas. Sem ela, todas.
    val xcf = XCFramework("GoatShared")
    val slices = System.getenv("GOATSHARED_SLICES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    fun wanted(name: String) = slices == null || name in slices
    listOfNotNull(
        if (wanted("tvos")) tvosArm64() else null,
        if (wanted("tvossim")) tvosSimulatorArm64() else null,
        if (wanted("ios")) iosArm64() else null,
        if (wanted("iossim")) iosSimulatorArm64() else null,
        if (wanted("macos")) macosArm64() else null,
    ).forEach { target ->
        target.binaries.framework {
            baseName = "GoatShared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        // As horas do catch-up (a hora de um fuso) e o JSON das definições: as mesmas bibliotecas nas duas plataformas.
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
