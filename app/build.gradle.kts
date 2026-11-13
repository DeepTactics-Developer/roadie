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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
