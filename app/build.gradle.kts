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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
