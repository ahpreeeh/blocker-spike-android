# Identite Android permanente d'Albugimed

## Contrat fige avant restauration

- Nom visible : `Albugimed`
- Application ID : `com.albugimed.app`
- Device Admin Receiver :
  `com.albugimed.app.admin.AlbugimedDeviceAdminReceiver`
- Premiere version : `versionCode 1`, `versionName 0.1.0`
- Alias de signature : `albugimed-app`
- Type de cle : RSA 4096 bits, SHA-256, validite 50 ans

L'interface, les fonctionnalites, l'architecture interne et le namespace Kotlin
peuvent etre remplaces. L'application ID, la chaine de signature et le composant
Device Owner permanent doivent rester compatibles.

## Fichiers secrets locaux

Le script `CREATE_ALBUGIMED_SIGNING_KEY.ps1` cree :

- `signing/albugimed-release.p12` : cle privee permanente ;
- `signing/albugimed-signing.properties` : configuration locale de build.

Ces fichiers sont ignores par Git. Ils ne doivent jamais etre ajoutes a un
commit, envoyes dans une discussion ou conserves sans chiffrement sur un espace
partage.

## Sauvegarde obligatoire avant transfert

Avant de transferer le Device Owner :

1. conserver la copie locale fonctionnelle ;
2. produire une copie chiffree du fichier `.p12` sur Google Drive ;
3. conserver le mot de passe dans un gestionnaire distinct ;
4. verifier que la copie sauvegardee peut etre relue ;
5. noter l'empreinte SHA-256 publique dans le protocole de migration.

La perte de la cle privee empecherait les mises a jour directes de l'application
installee. Aucun transfert ne doit donc etre declenche avant cette verification.
