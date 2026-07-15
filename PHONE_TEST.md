# Test T2 sur le Poco X7 Pro

Ce test produit le verdict de faisabilité du bloqueur. Il faut le faire sur le
Poco sous Android 16 ; une compilation seule ne valide pas T2.

## 1. Installation et diagnostic

1. Installer et ouvrir l'application.
2. Autoriser les notifications.
3. Activer **Albugimed Blocker Spike** dans les réglages d'accessibilité.
4. Régler la batterie sur **sans restriction**.
5. Laisser **T2-A** sélectionné au début. L'overlay n'est requis que pour T2-B.
6. Vérifier que les lignes correspondantes du diagnostic sont vertes.

Avant d'ajouter une règle, ouvrir Instagram puis TikTok et revenir au spike. Le
journal indique le package réellement vu par le Poco. Utiliser ces valeurs si
elles diffèrent des suggestions de l'interface.

## 2. S1 — 20 ouvertures nominales

1. Ajouter les deux packages à la liste bloquée.
2. Effacer le journal.
3. Ouvrir chaque application dix fois, en alternant les deux et en attendant
   environ deux secondes entre les essais pour ne pas déclencher l'anti-rebond.
4. Après chaque essai, vérifier le retour à l'accueil. Pour T2-A, toucher ensuite
   la notification pour vérifier que la page de demande s'ouvre.
5. Revenir au spike et relever la ligne **Mesures S1**.

Résultat à transmettre :

```text
T2-A — interceptions : __/20
Retours accueil OK : __/20
Médiane affichée : __ ms
Faux positifs observés : __
Notification visible à chaque fois : oui / non
```

Le délai affiché est une estimation entre la création de l'événement
d'accessibilité et l'appel de retour à l'accueil. Une capture vidéo reste utile
si la sensation réelle paraît nettement plus lente.

## 3. S2 — Autorisation temporaire

1. Depuis la page de blocage, choisir **Autoriser 2 minutes**.
2. Ouvrir plusieurs fois l'application pendant ces deux minutes : elle doit
   rester accessible.
3. Après expiration, la prochaine ouverture doit de nouveau être interceptée.

Résultat : `autorisation OK / expiration OK / anomalie : ...`

## 4. S3/S4 — Redémarrage et HyperOS

Effectuer dans cet ordre :

- redémarrer le téléphone puis tester une ouverture ;
- retirer le spike des applications récentes puis retester ;
- verrouiller l'écran trente minutes puis retester ;
- refaire ces essais avec le réglage batterie par défaut si tu veux mesurer la
  dépendance au mode **sans restriction**.

Noter si le service d'accessibilité reste vert et si le journal reçoit encore
les événements.

## 5. S5 — Override hors ligne

1. Passer en mode avion.
2. Activer **Override de panne** dans le spike.
3. Vérifier que les deux applications s'ouvrent.
4. Désactiver l'override et vérifier que le blocage revient.

## 6. Comparaison T2-B

1. Accorder l'autorisation **affichage au-dessus des apps**.
2. Sélectionner T2-B et effacer le journal.
3. Refaire 20 ouvertures.
4. Compter dans le journal :
   - `T2-B confirmé` : écran immédiat réellement affiché ;
   - `T2-B non confirmé` : lancement silencieusement refusé, notification utilisée ;
   - erreurs ou absence simultanée d'écran et de notification.

T2-B ne sera retenu que si l'écran est confirmé de façon stable après les tests
de redémarrage et de veille. Sinon T2-A reste une V1 acceptable et mesurable.
