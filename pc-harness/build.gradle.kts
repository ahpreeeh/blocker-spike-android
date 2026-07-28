plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.albugimed.pcharness.MainKt")
}

sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            include("com/albugimed/pcharness/**")
            include("com/albugimed/blockerspike/policy/PolicyModels.kt")
            include("com/albugimed/blockerspike/inference/StrictFlatJsonParser.kt")
            include("com/albugimed/blockerspike/inference/UnlockDecisionValidator.kt")
            include("com/albugimed/blockerspike/inference/UnlockPromptBuilder.kt")
        }
    }
}

dependencies {
    implementation(libs.litert.lm.jvm)
    implementation(libs.json)
}
