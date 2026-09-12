import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.jbdns"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.jbdns"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "4.2"
        resourceConfigurations += listOf("fa", "en")
    }

    signingConfigs {
        create("jbdns") {
            val p = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                p.load(f.inputStream())
                storeFile = rootProject.file(p.getProperty("storeFile") ?: "jbdns.keystore")
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val kc = signingConfigs.getByName("jbdns")
            if (kc.storeFile != null) signingConfig = kc
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "768m"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // رمزنگاری DNSCrypt: X25519، Ed25519، XSalsa20/ChaCha20-Poly1305 (R8 تنها بخش استفاده‌شده را نگه می‌دارد)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // org.json واقعی برای تست‌های واحد (نسخهٔ اندروید stub است)
}
