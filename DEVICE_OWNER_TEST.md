# Test Device Owner sur le Poco X7 Pro

> Mise a jour du 24 juillet 2026 : le passage du spike vers l'identite permanente
> `com.albugimed.app` est prepare dans `ALBUGIMED_V0_MIGRATION.md`. La presente
> procedure reste l'historique de validation du spike.

Ce protocole valide la suspension Android officielle. Une simple installation ne
suffit pas : `setPackagesSuspended()` ne fonctionne que lorsque le spike est
proprietaire de l'appareil.

## Garde-fous du prototype

- L'APK installee manuellement n'est pas `testOnly`, car l'installateur HyperOS
  refuse les paquets de test hors ADB.
- Une sortie interne desuspend les cibles connues puis appelle
  `DevicePolicyManager.clearDeviceOwnerApp()`. Cette methode est reservee aux
  tests et la sortie n'a pas encore ete executee sur le Poco provisionne.
- L'override de panne reste present. Il desactive volontairement toutes les
  suspensions.
- Ces deux sorties rendent le test recuperable, mais signifient que cette version
  n'est pas encore un bloqueur final sans contournement.

## 1. Verification avant reinitialisation

L'ancienne build `testOnly` a valide le certificat et le recepteur avant la remise
a zero. La build effectivement provisionnee est :

`artifacts/device-owner-spike/app-manual-device-owner.apk`

SHA-256 :
`8994EED9B812085A87008EEB86304D3642C70C2DC7A53F4CB15E3434B683E8E2`

```powershell
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-active-admin --user 0 com.albugimed.blockerspike/.admin.BlockerDeviceAdminReceiver
adb shell dpm remove-active-admin --user 0 com.albugimed.blockerspike/.admin.BlockerDeviceAdminReceiver
```

Cette etape ne donne pas encore le droit de suspendre des applications. Elle
verifie seulement que l'APK, le certificat et le recepteur d'administration sont
acceptes par le Poco.

## 2. Provisionnement apres reinitialisation

1. Reinitialiser le telephone uniquement apres validation finale de la sauvegarde.
2. Pendant l'assistant initial, ne connecter aucun compte Google, Xiaomi ou autre.
3. Terminer l'assistant minimal, activer les options developpeur et le debogage USB,
   puis autoriser ce PC.
4. Copier l'APK non `testOnly` dans `Download` et l'installer manuellement depuis
   le gestionnaire de fichiers.
5. Sur HyperOS, activer temporairement un compte Xiaomi pour autoriser
   **Debogage USB (parametres de securite)**, puis redemarrer le telephone.
6. Retirer completement le compte Xiaomi et verifier `Accounts: 0` avant le
   provisionnement.
7. Definir le paquet deja installe comme Device Owner :

```powershell
adb shell dpm set-device-owner --user 0 com.albugimed.blockerspike/.admin.BlockerDeviceAdminReceiver
adb shell dpm list-owners
```

La variante DPM d'HyperOS testee ne reconnait pas l'option facultative `--name`.

8. Dans le diagnostic du bloqueur, ouvrir **Alarmes exactes** et autoriser
   **Alarmes et rappels**. Sans ce droit, le repli `setAndAllowWhileIdle()` peut
   depasser sensiblement la duree demandee.

Le composant exact est :
`com.albugimed.blockerspike/.admin.BlockerDeviceAdminReceiver`.

Si `set-device-owner` signale qu'un compte existe deja, ne pas tenter de contourner
l'erreur : le telephone n'est plus dans l'etat de provisionnement requis.

## 3. Validation fonctionnelle

1. Test nominal realise avec `com.miui.calculator` : Android a confirme
   `suspended=true` et le lancement a ete bloque.
2. Installer Instagram, puis verifier qu'il apparait comme suspendu.
3. Verifier que l'icone ne permet pas d'ouvrir Instagram et que ses notifications
   sont masquees.
4. Accorder deux minutes : Instagram doit redevenir accessible, puis etre suspendu
   a l'expiration, y compris ecran verrouille.
5. Redemarrer le Poco et verifier que la suspension est reappliquee.
6. Tester l'override hors ligne, puis le desactiver.

Resultat intermediaire avec la calculatrice : apres autorisation des alarmes
exactes, Android a ressuspendu le package sans reouverture du bloqueur. La mesure
ADB a observe 108,75 s apres sa premiere detection, mais le moniteur a demarre
environ onze secondes apres le clic ; la precision clic-a-clic doit etre mesuree
par un journal interne `granted_at / allowed_until / enforced_at`.

L'accessibilite, l'overlay et le reglage batterie ne sont pas necessaires a la
suspension Device Owner. Ils restent dans l'APK uniquement pour comparer avec le
prototype T2 precedent.

## 4. Sortie de test

Dans l'application, utiliser **Retirer le Device Owner (secours)**. Le controleur
tente d'abord de desuspendre chaque cible connue, puis appelle
`clearDeviceOwnerApp()`.

Cette sortie ne doit etre testee qu'au moment ou la perte du role est acceptee :
un Device Owner ne peut pas etre reattribue apres la configuration sans nouvelle
remise a zero. Une future version anti-contournement devra proteger cette sortie
par une politique explicite plutot que la laisser directement accessible.
