import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val permanentSigningFile = rootProject.file("signing/albugimed-signing.properties")
val permanentSigning = Properties().apply {
    if (permanentSigningFile.isFile) {
        permanentSigningFile.inputStream().use(::load)
    }
}
val permanentCertificateSha256 =
    permanentSigning.getProperty("certificateSha256", "UNCONFIGURED")

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
            versionCode = 3
            versionName = "0.1.2-backup-enabled"
            manifestPlaceholders["deviceAdminReceiverClass"] =
                "com.albugimed.app.admin.AlbugimedDeviceAdminReceiver"
            manifestPlaceholders["applicationLabel"] = "Albugimed"
            buildConfigField("boolean", "PERMANENT_IDENTITY", "true")
        }
    }

    signingConfigs {
        if (permanentSigningFile.isFile) {
            create("permanent") {
                storeFile = rootProject.file(permanentSigning.getProperty("storeFile"))
                storePassword = permanentSigning.getProperty("storePassword")
                keyAlias = permanentSigning.getProperty("keyAlias")
                keyPassword = permanentSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (permanentSigningFile.isFile) {
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
            (identity == "migration" && variant.buildType == "release")
        ) {
            variant.enable = false
        }
    }
}

tasks.configureEach {
    if (name == "assemblePermanentRelease" && !permanentSigningFile.isFile) {
        doFirst {
            error(
                "Signature permanente absente. Executez d'abord " +
                    "CREATE_ALBUGIMED_SIGNING_KEY.ps1."
            )
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
    implementation(libs.litert.lm.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
