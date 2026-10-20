import java.util.Properties
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.google.devtools.ksp")
}
// 签名信息从 ../signing/signing.properties 读取（不进源码包、不进 git）
val signProps = Properties().apply {
  val f = rootProject.file("../signing/signing.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
android {
  namespace = "com.scarlett.luobi"
  compileSdk = 35
  defaultConfig {
    applicationId = "com.scarlett.luobi"
    minSdk = 29; targetSdk = 35; versionCode = 6; versionName = "1.6.0"
  }
  signingConfigs {
    if (signProps.isNotEmpty()) create("release") {
      storeFile = rootProject.file("../signing/" + signProps.getProperty("storeFile"))
      storePassword = signProps.getProperty("storePassword")
      keyAlias = signProps.getProperty("keyAlias")
      keyPassword = signProps.getProperty("keyPassword")
    }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = true; isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (signProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
    }
  }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
  val bom = platform("androidx.compose:compose-bom:2024.09.00")
  implementation(bom)
  implementation("androidx.compose.material3:material3")
  implementation("dev.chrisbanes.haze:haze:1.2.2")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  ksp("androidx.room:room-compiler:2.6.1")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20231013")
}
