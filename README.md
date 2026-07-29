# Blocker Spike — Phase 5 T2 (V0)

Prototype local Kotlin/Compose pour Android 16 (API 36), ciblé Poco X7 Pro / HyperOS.
Implémente le protocole `Phase-5-T2-Protocole-bloqueur-Android-V0.md` : détection du
package au premier plan via `AccessibilityService`, état local déterministe (DataStore),
interruption T2-A (notification) ou T2-B (écran immédiat), autorisations temporaires
avec expiration, override de panne, diagnostic et journal minimal.

## Branche canonique T3

Depuis le 29 juillet 2026, `feature/albugimed-v0` est la source canonique pour
l'identité Device Owner permanente et l'inférence locale LiteRT-LM. La branche
`agent/t3-v1-inference` est un spike historique déjà consolidé sélectivement :
ne pas la fusionner ni la cherry-pick en bloc dans l'application permanente.

## Ouvrir et compiler

1. Android Studio (Narwhal ou plus récent, JDK 17) → **File → Open** → ce dossier.
2. Synchroniser le projet. Le wrapper Gradle 8.14.3 est inclus ; aucune installation
   globale de Gradle n'est nécessaire.
3. Lancer `./gradlew test` (Windows : `gradlew.bat test`).
4. Brancher le Poco (débogage USB activé) → **Run 'app'**.

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
  `SYSTEM_ALERT_WINDOW` est accordé. Le simple retour de `startActivity()` ne
  prouve pas que l'écran s'est affiché : l'activité confirme son ouverture avec
  un token et, sans confirmation sous 750 ms, le service se replie sur la
  notification. La stabilité reste à mesurer sur HyperOS.
- **Stockage illisible** : le flux de politique émet `storageHealthy = false`
  et aucun blocage (fail-open) — l'utilisateur n'est jamais enfermé sans
  recours, le diagnostic l'affiche, l'override reste accessible (§5, S5).
- **Journal en mémoire** : il enregistre aussi les changements de politique,
  le succès du retour accueil et une latence estimée depuis l'événement Android.
  Il reste volontairement non persistant ; un journal vide
  après une longue veille est aussi un signal de kill HyperOS (S3/S4).
- **`minSdk = 36`** : le spike ne vise que le Poco sous Android 16 ; à abaisser
  si un second appareil de test apparaît.
- **Horloge injectée** (`TimeSource`) : permet de tester l'expiration (S2)
  sans attendre en temps réel dans de futurs tests unitaires.

## Scénarios de test

Suivre [PHONE_TEST.md](PHONE_TEST.md) pour S1 → S5 et confronter aux critères :
≥ 19/20 interceptions, délai médian < 1 s, zéro faux positif, état conservé
après redémarrage, override fonctionnel hors ligne.

## Addendum Device Owner — 24 juillet 2026

La voie accessibilité/overlay n'ayant pas été suffisamment fiable sur HyperOS,
le spike applique maintenant la politique avec
`DevicePolicyManager.setPackagesSuspended()` lorsque l'application est Device
Owner. Le Poco a confirmé `suspended=true` pour `com.miui.calculator` et a bloqué
son lancement.

Le cœur coercitif est validé pour le niveau spike. Restent à tester : expiration
d'une autorisation temporaire, redémarrage/veille, notifications, Instagram,
override et compatibilité des applications critiques. Voir
[DEVICE_OWNER_TEST.md](DEVICE_OWNER_TEST.md).
