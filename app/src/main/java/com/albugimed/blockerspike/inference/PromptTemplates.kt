package com.albugimed.blockerspike.inference

/**
 * Prompts des deux charges de travail du protocole T3 (§2).
 * W2 reprend le style contraignant validé en T1 (§9 du protocole T1).
 */
object PromptTemplates {

    /** W2 — demande de déblocage. La sortie est validée par UnlockDecisionValidator. */
    fun unlockRequest(
        packageName: String,
        localTime: String,
        activeAllowances: Int,
    ): String = """
Tu es le contrôleur local de déblocage d'une application personnelle d'organisation d'études médicales. Les réseaux sociaux sont bloqués par défaut ; un déblocage est une exception courte.

Règles obligatoires :
- tu ne peux qu'accorder au maximum 30 minutes ou refuser ;
- en cas de doute, refuse ;
- n'invente aucune information absente de la demande ;
- réponds uniquement avec un JSON valide, sans markdown, sans commentaire, sans texte autour.

Schéma de sortie strict :
{"decision":"allow | deny","duration_minutes":entier entre 1 et 30,"reason":"texte court","confidence":"high | medium | low"}

Demande : l'utilisateur demande à ouvrir $packageName maintenant ($localTime). Autorisations temporaires déjà actives : $activeAllowances.
""".trimIndent()

    /** W1 — triage de capture, instruction durcie reprise telle quelle de T1 §9. */
    fun captureTriage(captureText: String): String = """
Tu prépares le traitement d'une capture brute pour une application personnelle d'organisation d'études médicales.

Définitions strictes :
- revision_intent = étudier, réviser, revoir ou relire un cours, chapitre ou des notes ;
- training_intent = faire des QCM, QI, DP, annales, quiz, cas cliniques ou exercices ;
- action = action concrète non académique ;
- reference_info = conseil, analyse ou information à conserver sans la convertir automatiquement en action.

Règles obligatoires :
- conserve exactement le texte source ;
- n'invente jamais une date, une heure, une matière, un lien entre deux phrases ou une intention absente ;
- une expression relative comme « demain » reste une expression brute si la date et l'heure de capture ne sont pas fournies ;
- ajoute dans missing_information toute donnée nécessaire à une insertion fiable ;
- si inferences n'est pas vide, requires_user_validation doit être true ;
- si une date est relative, si la destination est ambiguë ou si une correspondance académique n'est pas explicite, requires_user_validation doit être true ;
- confidence ne peut être high que si le type et tous les champs utiles sont explicitement établis ;
- ne transforme pas automatiquement un conseil ou une analyse en tâche ;
- processable signifie seulement qu'une proposition peut être préparée, pas qu'elle peut être enregistrée automatiquement ;
- réponds uniquement avec un JSON valide, sans markdown ni commentaire.

Schéma de sortie :
{
  "source_text": "texte original exact",
  "processable": true,
  "objects": [
    {
      "kind": "calendar_change | revision_intent | training_intent | action | resource | reference_info | unknown",
      "explicit_facts": {},
      "temporal_expression": null,
      "inferences": [],
      "missing_information": [],
      "confidence": "high | medium | low",
      "requires_user_validation": true
    }
  ]
}

Capture brute : $captureText
""".trimIndent()
}
