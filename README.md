# Blocker Spike — Phase 5 T2 (V0)

Prototype local Kotlin/Compose pour Android 16 (API 36), ciblé Poco X7 Pro / HyperOS.
Implémente le protocole `Phase-5-T2-Protocole-bloqueur-Android-V0.md` : détection du
package au premier plan via `AccessibilityService`, état local déterministe (DataStore),
interruption T2-A (notification) ou T2-B (écran immédiat), autorisations temporaires
avec expiration, override de panne, diagnostic et journal minimal.

## Ouvrir et compiler

1. Android Studio (Narwhal ou plus récent, JDK 17) → **File → Open** → ce dossier.
2. Le binaire `gradle-wrapper.jar` n'est pas inclus (fichier binaire). Deux options :
   - Android Studio propose en général de régénérer le wrapper au premier sync ;
   - ou, si Gradle est installé : `gradle wrapper --gradle-version 8.14.3` à la racine.
3. Brancher le Poco (débogage USB activé) → **Run 'app'**.

## Arborescence

```
app/src/main/java/com/albugimed/blockerspike/
├── App.kt                          # Application + canal de notification + Graph (DI minimal)
├── MainActivity.kt                 # Écran principal (diagnostic, règles, journal)
├── policy/
│   ├── PolicyModels.kt             # PolicyState + shouldBlock() + TimeSource injectable
│   └── BlockPolicyRepository.kt    # Persistance DataStore, fail-open si stockage illisible
├── service/
│   └── BlockerAccessibilityService.kt  # TYPE_WINDOW_STATE_CHANGED → interruption
├── gate/
│   └── BlockGateActivity.kt        # Écran neutre post-interception, autorisations de test
├── diagnostics/
│   └── Diagnostics.kt              # Vérification des accès (protocole §6) + intents réglages
└── log/
    └── InterceptionLog.kt          # Journal en mémoire (200 entrées)
```

## Mise en route sur le téléphone

1. Lancer l'app → écran **Diagnostic** : tout doit passer au vert.
   - Service d'accessibilité : Réglages → Accessibilité → « Albugimed Blocker Spike ».
   - Notifications : demandées au premier lancement.
   - Affichage au-dessus des apps : requis seulement pour tester T2-B.
   - Batterie sans restriction : indispensable sous HyperOS (scénario S4).
2. Section **Packages bloqués** : ajouter deux packages non critiques (chips
   Instagram/TikTok pré-remplies, ou n'importe quel package pour S1).
3. Ouvrir l'application ciblée → retour accueil + notification (T2-A) ou écran
   de blocage (T2-B si la variante est activée et l'overlay accordé).

## Choix d'implémentation à connaître

- **T2-B** : le lancement direct depuis le service n'est tenté que si
  `SYSTEM_ALERT_WINDOW` est accordé (exemption de lancement en arrière-plan
  d'Android 16) ; sinon repli automatique sur la notification, et l'échec est
  journalisé. C'est exactement le comparatif demandé par le protocole §4.
- **Stockage illisible** : le flux de politique émet `storageHealthy = false`
  et aucun blocage (fail-open) — l'utilisateur n'est jamais enfermé sans
  recours, le diagnostic l'affiche, l'override reste accessible (§5, S5).
- **Journal en mémoire** : volontairement non persistant ; un journal vide
  après une longue veille est aussi un signal de kill HyperOS (S3/S4).
- **`minSdk = 36`** : le spike ne vise que le Poco sous Android 16 ; à abaisser
  si un second appareil de test apparaît.
- **Horloge injectée** (`TimeSource`) : permet de tester l'expiration (S2)
  sans attendre en temps réel dans de futurs tests unitaires.

## Scénarios de test

Suivre S1 → S5 du protocole (§7) et confronter aux critères du §8 :
≥ 19/20 interceptions, délai médian < 1 s, zéro faux positif, état conservé
après redémarrage, override fonctionnel hors ligne.
