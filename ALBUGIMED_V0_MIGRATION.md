# Migration vers Albugimed V0 permanente

**Date de gel :** 24 juillet 2026  
**Statut :** APK compilees, signatures verifiees et cle sauvegardee ; essais sur
telephone encore requis.

## Identite permanente

- Nom visible : `Albugimed`
- Application ID : `com.albugimed.app`
- Version initiale : `versionCode 1`, `versionName 0.1.0`
- Device Admin Receiver :
  `com.albugimed.app.admin.AlbugimedDeviceAdminReceiver`
- Certificat SHA-256 :
  `15615F9F125916891817DED195773AE825650C4BD7FCD4A577E48363EA43D39E`
- Cle : RSA 4096 bits, certificat auto-signe valide 50 ans

L'Application ID, la chaine de signature et le nom qualifie du recepteur sont le
contrat de compatibilite. Le reste du code et de l'interface peut etre reecrit.

## Artefacts geles

### Mise a jour du spike qui cede le role

- Fichier : `artifacts/albugimed-v0/blocker-spike-owner-migration.apk`
- Application ID : `com.albugimed.blockerspike`
- Version : `versionCode 3`, `versionName 0.3-owner-transfer`
- SHA-256 APK :
  `A0C61170A80E891A66E5963F4A77EF0A16776F8E293BEAD0D3100A0A0B0C8661`
- Certificat SHA-256 :
  `8D4391DB015D2C9F2ACA0A45CF895E9815ED77C7C721C616D26F49F7E95F1EE7`

Ce certificat est identique a celui de
`artifacts/device-owner-spike/app-manual-device-owner.apk`.

### V0 permanente

- Fichier : `artifacts/albugimed-v0/albugimed-v0.1.0-release.apk`
- SHA-256 APK :
  `68661F8E8E6B98B6C2708F5627933733C587D05C9837B1F79130FEDBC2DC7001`
- Certificat SHA-256 :
  `15615F9F125916891817DED195773AE825650C4BD7FCD4A577E48363EA43D39E`
- APK release non debuggable et non `testOnly`

### Mise a jour de preuve

- Fichier : `artifacts/albugimed-v0/albugimed-v0.1.1-update-proof.apk`
- Version : `versionCode 2`, `versionName 0.1.1-update-proof`
- SHA-256 APK :
  `51B04D5B3FC5530C50C659C2FCBAC59A0DF610F2384B294B25E392B9717C4240`
- Certificat SHA-256 : identique a la V0 0.1.0

Cette APK ne doit etre installee qu'apres la V0 0.1.0 et le transfert. Elle
affiche son numero de version afin de prouver visiblement que la mise a jour a
remplace le code sans perdre l'identite Android.

## Cle locale

- Cle privee : `signing/albugimed-release.p12` (ignoree par Git)
- Configuration : `signing/albugimed-signing.properties` (ignoree par Git)
- SHA-256 du fichier `.p12` :
  `BC0235E0CA36173A0569F758B28255C143B7AE53EE0EE1F1AA7675983F3C6B77`

Ne jamais copier le fichier de configuration avec son mot de passe vers Drive.
Sauvegarder uniquement le `.p12`, deja chiffre par son mot de passe, et conserver
ce mot de passe separement.

### Copie Drive verifiee

- Dossier : `Albugimed - sauvegarde cle de signature`
- Fichier : `albugimed-release-2026-07-24.p12`
- Taille distante : `4358` octets
- SHA-256 relu depuis Drive :
  `BC0235E0CA36173A0569F758B28255C143B7AE53EE0EE1F1AA7675983F3C6B77`
- Partage public : aucun

La copie distante a donc exactement les memes octets que la cle locale. Le
fichier `albugimed-signing.properties`, qui contient le mot de passe utilise par
Gradle, n'a pas ete envoye.

## Conditions obligatoires avant transfert

1. Confirmer que le mot de passe est conserve separement.
2. Connecter le Poco en ADB et verifier le proprietaire et le certificat installes.
3. Mettre a jour le spike avec l'APK de migration.
4. Installer la V0 permanente.
5. Activer son recepteur comme administrateur, sans en faire un second owner :

```powershell
adb shell dpm set-active-admin --user 0 `
  com.albugimed.app/com.albugimed.app.admin.AlbugimedDeviceAdminReceiver
```

6. Lancer le transfert depuis le bouton du spike.
7. Verifier avec `adb shell dpm list-owners` que le seul owner est la V0.
8. Tester suspension, autorisation temporaire, expiration, ecran verrouille et
    redemarrage.
9. Installer une V0 de preuve avec un `versionCode` superieur, signee par la
    meme cle, et verifier que le Device Owner survit a la mise a jour.
10. Restaurer les donnees uniquement apres tous ces controles.

`transferOwnership()` est atomique : si le transfert echoue, le spike doit rester
le Device Owner. Ne jamais utiliser le bouton de suppression du Device Owner pour
tenter de resoudre un echec de transfert.
