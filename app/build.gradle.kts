import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val permanentSigningFile = rootProject.file("signing/albugimed-signing.properties")
val permanentSigning = Properties().apply {
    if (permanentSigningFile.isFile) {
        permanentSigningFile.inputStream().use(::load)
    }
}
val permanentCertificateSha256 =
    permanentSigning.getProperty("certificateSha256", "UNCONFIGURED")
val permanentStoreFile = permanentSigning.getProperty("storeFile")
    ?.let { rootProject.file(it) }
val permanentSigningReady =
    permanentSigningFile.isFile &&
        permanentStoreFile?.isFile == true &&
        permanentSigning.getProperty("storePassword").isNullOrBlank().not() &&
        permanentSigning.getProperty("keyAlias").isNullOrBlank().not() &&
        permanentSigning.getProperty("keyPassword").isNullOrBlank().not() &&
        permanentCertificateSha256 != "UNCONFIGURED"

android {
    // Le namespace du code peut evoluer apres la migration. L'applicationId
    // permanent et le composant Device Owner, eux, ne changeront plus.
    namespace = "com.albugimed.blockerspike"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        targetSdk = 36
        buildConfigField(
            "String",
            "PERMANENT_OWNER_CERT_SHA256",
            "\"$permanentCertificateSha256\"",
        )
    }

    flavorDimensions += "identity"
    productFlavors {
        create("migration") {
            dimension = "identity"
            applicationId = "com.albugimed.blockerspike"
            versionCode = 4
            versionName = "0.3.1-owner-transfer-fix"
            manifestPlaceholders["deviceAdminReceiverClass"] =
                "com.albugimed.blockerspike.admin.BlockerDeviceAdminReceiver"
            manifestPlaceholders["applicationLabel"] = "Blocker Spike - migration"
            buildConfigField("boolean", "PERMANENT_IDENTITY", "false")
        }
        create("permanent") {
            dimension = "identity"
            applicationId = "com.albugimed.app"
            versionCode = 9
            versionName = "0.7.0-refonte"
            manifestPlaceholders["deviceAdminReceiverClass"] =
                "com.albugimed.app.admin.AlbugimedDeviceAdminReceiver"
            manifestPlaceholders["applicationLabel"] = "Albugimed"
            buildConfigField("boolean", "PERMANENT_IDENTITY", "true")
        }
    }

    signingConfigs {
        if (permanentSigningReady) {
            create("permanent") {
                storeFile = permanentStoreFile
                storePassword = permanentSigning.getProperty("storePassword")
                keyAlias = permanentSigning.getProperty("keyAlias")
                keyPassword = permanentSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (permanentSigningReady) {
                signingConfig = signingConfigs.getByName("permanent")
            }
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

androidComponents {
    beforeVariants { variant ->
        val identity = variant.productFlavors
            .firstOrNull { it.first == "identity" }
            ?.second
        // Ne jamais produire une V0 signee en debug ni une migration signee
        // avec la cle permanente.
        if ((identity == "permanent" && variant.buildType == "debug") ||
            (identity == "migration" && variant.buildType == "release") ||
            (identity == "permanent" && variant.buildType == "release" &&
                !permanentSigningReady)
        ) {
            variant.enable = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.datastore.preferences)
    // V2.1. Room porte la file des captures, WorkManager son envoi. Contrat
    // §10 point 2 : ni l'un ni l'autre n'émet de requête propre — WorkManager
    // ne fait qu'ordonnancer `CaptureUploadWorker`, qui passe par le même
    // `HttpSyncTransport` que le reste. La surface réseau reste un fichier.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.litert.lm.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
