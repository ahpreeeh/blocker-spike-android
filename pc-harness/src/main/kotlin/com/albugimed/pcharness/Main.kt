package com.albugimed.pcharness

import com.albugimed.blockerspike.inference.PromptTemplates
import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.policy.UnlockDecisionValidator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONObject

/**
 * Harnais T3 sur PC : exécute le pipeline réel prompt -> Gemma 3n E4B
 * (litertlm-jvm, CPU) -> UnlockDecisionValidator, sans téléphone.
 * Les latences PC ne sont PAS transposables au Poco ; seule la conformité
 * fonctionnelle compte ici.
 *
 * Usage : gradlew :pc-harness:run --args="<chemin-modele> <nb-essais-W2>"
 */
fun main(args: Array<String>) {
    val modelPath = args.getOrNull(0) ?: """D:\Gemma E4B copie\gemma-4-E4B-it.litertlm"""
    val tries = args.getOrNull(1)?.toIntOrNull() ?: 2

    println("=== Harnais T3 PC — modèle : $modelPath ===")

    val t0 = System.currentTimeMillis()
    val engine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
    engine.initialize()
    println("Chargement moteur : ${System.currentTimeMillis() - t0} ms")

    val pkg = "com.instagram.android"
    val policy = PolicyState(blockedPackages = setOf(pkg))
    var accepted = 0

    // --- W2 : demandes de déblocage ---
    repeat(tries) { i ->
        val prompt = PromptTemplates.unlockRequest(
            packageName = pkg,
            localTime = "jeudi 14:0$i",
            activeAllowances = 0,
        )
        val t1 = System.currentTimeMillis()
        val conversation = engine.createConversation()
        val raw = try {
            conversation.sendMessage(prompt).toString()
        } finally {
            runCatching { conversation.close() }
        }
        val genMs = System.currentTimeMillis() - t1
        val outcome = UnlockDecisionValidator.validate(raw, pkg, policy)
        if (outcome !is UnlockDecisionValidator.Outcome.Rejected) accepted++
        println()
        println("--- W2 essai ${i + 1}/$tries — génération $genMs ms ---")
        println("SORTIE BRUTE : $raw")
        println("VERDICT     : $outcome")
    }

    // --- W1 : un triage de capture (cas A du protocole T1) ---
    val capturePrompt = PromptTemplates.captureTriage(
        "L'ED de pneumo est déplacé à jeudi à la même heure."
    )
    val t2 = System.currentTimeMillis()
    val conv = engine.createConversation()
    val w1raw = try {
        conv.sendMessage(capturePrompt).toString()
    } finally {
        runCatching { conv.close() }
    }
    println()
    println("--- W1 cas T1-A — génération ${System.currentTimeMillis() - t2} ms ---")
    println("SORTIE BRUTE : $w1raw")
    val w1ok = runCatching {
        val json = JSONObject(w1raw.trim())
        json.has("source_text") && json.has("processable") && json.has("objects")
    }.getOrDefault(false)
    println("JSON W1 conforme au schéma de haut niveau : $w1ok")

    engine.close()
    println()
    println("=== Bilan : W2 conformes (allow/deny validés) $accepted/$tries — W1 JSON $w1ok ===")
}
