plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.albugimed.pcharness.MainKt")
}

// Réutilise les MÊMES sources que l'app (aucune duplication de logique) :
// seuls les fichiers purs (sans import Android) sont inclus.
sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include("com/albugimed/pcharness/**")
            include("com/albugimed/blockerspike/policy/PolicyModels.kt")
            include("com/albugimed/blockerspike/policy/UnlockDecisionValidator.kt")
            include("com/albugimed/blockerspike/inference/PromptTemplates.kt")
        }
    }
}

dependencies {
    implementation(libs.litertlm.jvm)
    implementation(libs.org.json)
}
