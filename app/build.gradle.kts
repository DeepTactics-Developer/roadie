import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.deeptactics.roadie"
    compileSdk = 37
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.deeptactics.roadie"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 음원은 원본 폴더를 그대로 assets로 쓴다
    sourceSets["main"].assets.srcDir("../assets_src/music")

    // 키스토어 정보는 저장소 밖 파일(keystore.properties)에서 읽는다
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
