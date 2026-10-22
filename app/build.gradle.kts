import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名信息不进仓库，从 local.properties（或环境变量）读取。
// 自备 keystore 后在本机 local.properties 里写，路径相对项目根：
//   komo.storeFile=app/release.jks
//   komo.storePassword=...
//   komo.keyAlias=...
//   komo.keyPassword=...
// 也可用环境变量 KOMO_STORE_FILE / KOMO_STORE_PASSWORD / KOMO_KEY_ALIAS / KOMO_KEY_PASSWORD。
// 缺任一项时不做 release 签名，assembleDebug 照常可用。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val storePath = secret("komo.storeFile", "KOMO_STORE_FILE")
val storePass = secret("komo.storePassword", "KOMO_STORE_PASSWORD")
val keyAliasName = secret("komo.keyAlias", "KOMO_KEY_ALIAS")
val keyPass = secret("komo.keyPassword", "KOMO_KEY_PASSWORD")
val canSign = storePath != null && storePass != null && keyAliasName != null && keyPass != null

android {
    namespace = "com.dmanime.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dmanime.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // 弹弹play 开放平台凭证也不进仓库，本机 local.properties 里写：
        //   komo.dandanplay.appId=...
        //   komo.dandanplay.secret=...
        // 或环境变量 KOMO_DANDANPLAY_APP_ID / KOMO_DANDANPLAY_SECRET。
        // 没配时弹幕界面里不会出现「弹弹play」这个来源。
        val ddpAppId = secret("komo.dandanplay.appId", "KOMO_DANDANPLAY_APP_ID") ?: ""
        val ddpSecret = secret("komo.dandanplay.secret", "KOMO_DANDANPLAY_SECRET") ?: ""
        buildConfigField("String", "DANDANPLAY_APP_ID", "\"$ddpAppId\"")
        buildConfigField("String", "DANDANPLAY_SECRET", "\"$ddpSecret\"")
    }

    // 签名信息不进仓库。自备 keystore 后在本机 local.properties 里写（路径相对项目根）：
    //   komo.storeFile=app/release.jks
    //   komo.storePassword=...
    //   komo.keyAlias=...
    //   komo.keyPassword=...
    // 也可以用同名环境变量 KOMO_STORE_FILE / KOMO_STORE_PASSWORD / KOMO_KEY_ALIAS / KOMO_KEY_PASSWORD。
    // 缺任一项时不做 release 签名（assembleDebug 照常可用）。

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(storePath!!)
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // debug 也用同一份 key：这样 debug 包能直接覆盖装到 release 包上，
        // 不用卸载（卸载会丢追番记录、下载的剧集和导进来的规则）。
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:rules"))
    implementation(project(":core:sniff"))
    implementation(project(":core:catalog"))
    implementation(project(":core:danmaku"))
    implementation(project(":core:update"))

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
