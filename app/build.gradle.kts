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
        versionCode = 17
        versionName = "4.3"
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
        viewBinding = false
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "512m"
            }
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // رمزنگاری DNSCrypt: X25519، Ed25519، XSalsa20/ChaCha20-Poly1305 (R8 تنها بخش استفاده‌شده را نگه می‌دارد)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.json:json:20240303") // org.json واقعی برای تست‌های واحد
}
