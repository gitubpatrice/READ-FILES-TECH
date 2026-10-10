# Politique de confidentialité — Read Files Tech

**Version du document** : 10 octobre 2026 (v2.15.3)
**App** : Read Files Tech
**Site officiel** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Code source** : https://github.com/gitubpatrice/READ-FILES-TECH
**Licence du code** : Apache License 2.0

---

## 1. Objet

La présente Politique de confidentialité explique comment l'application **Read Files Tech** traite les données, fichiers et permissions de l'utilisateur.

## 2. Résumé pour l'utilisateur

- ✅ **Aucune publicité** dans l'application.
- ✅ **Aucun traceur**, mesure d'audience, analyse comportementale ou profilage.
- ✅ **Aucun compte** propre à l'application.
- ✅ Les fichiers ouverts, lus ou traités **restent sur l'appareil**.
- ✅ Les transmissions interviennent **uniquement après une action explicite** de l'utilisateur (partage, export) ou via un service tiers volontaire.
- ✅ **Aucune permission réseau** depuis la v2.15.3 : l'application n'ouvre aucune connexion d'elle-même (voir §6 bis et §9).

**Principe général** : Read Files Tech est un lecteur/explorateur local pour TXT, MD, JSON, HTML, CSS, JS, PHP, XML, CSV, DOCX, XLSX, PDF, ZIP et images. Tous les fichiers sont traités localement sous le contrôle de l'utilisateur.

## 3. Responsable / développeur

- **Développeur** : Patrice Haltaya (Files Tech)
- **Site internet** : https://www.files-tech.com
- **Contact confidentialité** : contact@files-tech.com
- **Dépôt source** : https://github.com/gitubpatrice/READ-FILES-TECH
- **Licence du code source** : Apache License 2.0

## 4. Données accessibles ou traitées

| Type de donnée                  | Utilisation                                                                                          | Lieu de traitement                  |
| ------------------------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------- |
| Fichiers choisis par l'utilisateur | Lecture, affichage, édition, conversion, partage à la demande de l'utilisateur.                   | Principalement local sur l'appareil. |
| Données techniques réseau       | **Aucune** de la part de l'application, qui ne détient aucune permission réseau depuis la v2.15.3 (voir §6 bis). Le partage et l'email confient le contenu à l'application choisie par l'utilisateur, sur son action. | Application ou service choisi par l'utilisateur. |
| Préférences locales             | Fichiers récents, réglages d'affichage, ordre de tri.                                                | Stockage local sur l'appareil.      |

## 5. Absence de publicité, traceurs et analyse

Le développeur déclare que l'application ne contient pas de publicité, de traceur, de mesure d'audience, d'analyse comportementale ou de système de profilage. L'application ne vend pas les données de l'utilisateur.

## 6. Partage et transmission de données

Les fichiers ou contenus ne sont transmis à un tiers que sur action explicite de l'utilisateur (bouton « Partager », export), via l'utilisation volontaire d'un service tiers, ou pour respecter une obligation légale applicable.

### Précisions

- Les rendus HTML/WebView affichent des contenus choisis par l'utilisateur ; rester prudent avec les fichiers de sources non fiables. JavaScript est **désactivé par défaut** pour les fichiers HTML (opt-in via la barre d'outils).
- L'application ne vérifie plus les mises à jour d'elle-même (retiré en v2.15.3). Le bouton « Vérifier les mises à jour » de l'écran « À propos » ouvre la page des versions dans le navigateur de l'utilisateur ; c'est le navigateur qui se connecte, pas l'application (voir §6 bis).

## 6 bis. Aucune connexion ouverte par l'application

**Jusqu'à la v2.15.2**, l'application ouvrait d'elle-même une connexion : au
lancement (au plus une fois toutes les 12 heures, résultat mis en cache) et à la
demande depuis l'écran « À propos », une requête `GET` vers
`https://api.github.com/repos/gitubpatrice/read-files-tech/releases/latest`,
pour savoir si une nouvelle version existait. Elle ne portait aucun identifiant
d'appareil, aucun compte, aucun cookie, aucune donnée d'usage, aucun nom de
fichier ; GitHub voyait l'adresse IP publique de l'appareil, comme n'importe quel
serveur que l'on contacte. Une version antérieure de ce document laissait croire
que toute activité réseau était déclenchée par l'utilisateur ; ce paragraphe a
été écrit pour le corriger.

**Depuis la v2.15.3, cette requête n'existe plus, et l'application ne détient
aucune permission réseau** : `INTERNET` et `ACCESS_NETWORK_STATE` sont absentes
de l'APK (§9). L'application ne se connecte à rien.

- **Ce qui la remplace** : le bouton « Vérifier les mises à jour » de l'écran
  « À propos » ouvre
  `https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest` dans le
  navigateur. C'est le navigateur qui se connecte, avec ses propres permissions
  et réglages — pas l'application.
- **Ce que cela coûte** : l'application ne prévient plus d'elle-même qu'une
  nouvelle version existe. Elle est distribuée par sideload, hors de tout magasin
  d'applications, donc aucun mécanisme du système ne le fait non plus. Pour
  apprendre qu'un correctif est sorti, vérifier depuis l'écran « À propos » ou
  sur https://www.files-tech.com.
- **Les liens ouverts depuis l'application** (site officiel, page des versions,
  email) sont confiés au navigateur ou à l'application de messagerie choisis par
  l'utilisateur, qui ouvrent leurs propres connexions.

L'application n'envoie elle-même aucun fichier, aucun contenu de document,
aucune donnée du coffre, nulle part. Le partage confie un fichier à
l'application choisie par l'utilisateur, sur son action (§6).

## 7. Conservation et suppression

Les fichiers restent sous le contrôle de l'utilisateur. Aucun compte propre à l'application n'est créé.

## 8. Sécurité

L'application met en place :

- aucune permission réseau : `INTERNET` et `ACCESS_NETWORK_STATE` sont retirées du manifeste fusionné, et la CI échoue si une permission réseau, ou toute permission hors de la liste revue, réapparaît (§9) ;
- une `network_security_config` refusant le trafic en clair et les autorités utilisateur ;
- une validation des chemins sur les MethodChannels natifs (Kotlin) ;
- une protection anti zip-slip sur les extractions d'archives ;
- des limites de taille pour prévenir un DoS local.

Voir [SECURITY.fr.md](https://github.com/gitubpatrice/READ-FILES-TECH/blob/main/SECURITY.fr.md) pour la politique de signalement.

## 9. Permissions Android

Liste établie par `aapt2 dump permissions` sur l'APK **release de la v2.15.3**,
et non d'après le manifeste source : des dépendances peuvent ajouter des
permissions au moment de la fusion des manifestes, et l'une des neuf ci-dessous
n'apparaît nulle part dans le manifeste propre de l'application. L'APK porte
**exactement neuf** permissions.

| Permission / accès                  | Raison                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`           | Fonction explorateur : parcourir, lire, éditer tout fichier choisi par l'utilisateur.                  |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | Même fonction, sur les versions d'Android antérieures à `MANAGE_EXTERNAL_STORAGE`. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Affichage et aperçu des fichiers médias choisis par l'utilisateur (Android 13+).                  |
| `CAMERA`                            | Scanner de documents et OCR (optionnel, accordée à la demande au runtime).                              |
| `REQUEST_INSTALL_PACKAGES`          | Installer une APK tapée dans l'explorateur. L'installation elle-même est faite par l'installeur du système, qui demande sa propre confirmation. |
| `com.readfilestech.read_files_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Interne à l'application, ajoutée à la fusion par la bibliothèque AndroidX. Déclarée au niveau de protection `signature`, elle garde privés les récepteurs de diffusion propres à l'application ; elle ne donne accès à rien en dehors de l'application. |
| `INTERNET`, `ACCESS_NETWORK_STATE`  | **Absentes depuis la v2.15.3.** Google ML Kit les ajoute à la fusion, via `com.google.android.datatransport:transport-backend-cct` ; l'application les retire par `tools:node="remove"`. La CI contrôle le manifeste fusionné de la variante release à chaque exécution et, dans le workflow de publication, avant toute mise en ligne (`tools/check-manifest-permissions.py`, avec contrôle négatif) : une permission réseau, ou toute permission hors des neuf ci-dessus, fait échouer la build. |

### 9 bis. Ce que « 100 % local » recouvre exactement

Les fichiers de l'utilisateur ne quittent jamais l'appareil : aucune fonction de
l'application ne les transmet, ni ne les téléverse, ni ne les sauvegarde à
distance. La reconnaissance de texte (OCR) s'exécute entièrement hors ligne — le
modèle est embarqué dans l'APK (`assets/mlkit-google-ocr-models`,
`libmlkit_google_ocr_pipeline.so`).

Deux réserves, énoncées ici parce qu'elles sont vérifiables sur l'APK :

1. **Google ML Kit embarque un transport de télémétrie, et il est neutralisé.**
   Les composants `TransportBackendDiscovery`, `JobInfoSchedulerService` et
   `AlarmManagerSchedulerBroadcastReceiver` pouvaient transmettre à Google des
   métriques d'usage de la bibliothèque — jamais le contenu des documents. Depuis
   la **v2.15**, leurs trois déclarations sont retirées du manifeste final par
   `tools:node="remove"` : sans point d'entrée déclaré, Android ne peut ni lier le
   service ni délivrer la diffusion, donc le transport ne démarre pas.
   **Mesuré sur l'APK publié** de la v2.15.1, pas seulement sur le fichier source :
   `aapt2 dump xmltree` n'y trouve **aucun** composant `datatransport` (contrôle
   positif : sept entrées `com.google.mlkit` bien présentes). Ce que cela ne fait
   pas : le **code** du transport reste dans l'APK — la chaîne apparaît deux fois
   dans `classes.dex`. Depuis la **v2.15.3**, l'application ne détient en outre
   aucune permission réseau (§9) : même démarré, le transport ne pourrait rien
   envoyer.
2. **Le scanner de documents s'appuie sur les services Google Play.**
   `play-services-mlkit-document-scanner` est un client léger : l'interface de
   numérisation et son modèle vivent dans les services Play, qui les téléchargent
   à la demande. Le scanner s'exécute donc dans **un autre processus**, les
   services Google Play, avec leurs propres permissions, hors du contrôle de
   l'application : l'absence de permission réseau décrite ici vaut pour Read
   Files Tech, pas pour les services Google Play. Sur un appareil sans services
   Google Play, le scanner ne fonctionne pas ; le reste de l'application, OCR
   compris, fonctionne normalement.

> **Correction, v2.15.** Ce document affirmait jusqu'ici que
> `REQUEST_INSTALL_PACKAGES` avait été retirée en v2.12.2. C'était exact à cette
> date, mais la permission a été **rétablie en v2.13.0** pour restaurer
> l'installation directe d'une APK depuis l'explorateur — ce que `SECURITY.md`
> consignait correctement. Le tableau ci-dessus est établi sur l'APK publié.
>
> **Correction de la correction, même version.** Une première rédaction de ce
> paragraphe retirait la vérification de mise à jour de la ligne `INTERNET`, au
> motif que « l'application ne déclare pas `INTERNET` dans son manifeste ».
> Le fait était exact, la conclusion fausse : Android accorde les permissions du
> manifeste **fusionné**, pas du manifeste source. La requête vers
> `api.github.com` partait donc bel et bien, en s'appuyant sans le dire sur une
> permission apportée par une dépendance de télémétrie. L'affirmation « non
> demandées par l'application » était le contraire de la vérité.
>
> Depuis la v2.15, `INTERNET` et `ACCESS_NETWORK_STATE` sont **déclarées
> explicitement** dans le manifeste source, avec leur motif. Une permission que
> l'application utilise doit être visible dans le fichier que l'on relit.
>
> **Changement, v2.15.3.** La « correction de la correction » et le paragraphe
> qui la suit décrivent les versions v2.15 à v2.15.2. En v2.15.3, la vérification de mise à jour a d'abord été
> retirée, puis les deux permissions ont été retirées du manifeste fusionné par
> `tools:node="remove"` — toujours visibles dans le manifeste source, désormais
> comme un retrait. Supprimer les lignes n'aurait pas suffi : ML Kit aurait
> rajouté les permissions à la fusion. Le tableau ci-dessus est établi sur l'APK
> release de la v2.15.3.

## 10. Enfants

L'application n'est pas spécifiquement destinée aux enfants et ne contient aucun mécanisme de publicité comportementale ou de profilage.

## 11. Modifications

Cette politique peut être mise à jour lors de l'évolution de l'application. La date en tête indique la version en vigueur.

## 12. Contact

📧 **contact@files-tech.com**
