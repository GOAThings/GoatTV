import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Chave de assinatura do release: keystore.properties na raiz (fora do git). Sem ele, o release sai sem assinatura.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "cc.slokker.itv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cc.slokker.itv"
        // As bibliotecas AndroidX atuais (Compose, Media3, tv-material) exigem minSdk 23.
        minSdk = 23
        targetSdk = 35
        versionCode = 17
        versionName = "0.8.1"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // O debug instala-se ao lado do release ("i-tv debug"), para testar sem mexer na app do dia a dia.
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // Otimização da app inteira. Não usar `packageScope`: otimizar só alguns pacotes deixou classes
            // a estender classes privadas de outro pacote (IllegalAccessError no Android 11 da SHIELD).
            optimization {
                enable = true
            }
        }
        // Release com R8 mas depurável (modo de teste disponível), para validar o build final num emulador.
        create("staging") {
            initWith(getByName("release"))
            // -PstagingDebuggable=false: igual ao release também aqui (medir a fluidez na TV; sem run-as nem modo de teste).
            isDebuggable = providers.gradleProperty("stagingDebuggable").orNull != "false"
            applicationIdSuffix = ".staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.tvprovider)

    testImplementation(libs.junit)
}
