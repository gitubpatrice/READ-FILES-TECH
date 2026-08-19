# Politique de confidentialité — Read Files Tech

**Version du document** : 9 août 2026 (v2.15)
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

**Principe général** : Read Files Tech est un lecteur/explorateur local pour TXT, MD, JSON, HTML, CSS, JS, PHP, XML, CSV, DOCX, XLSX, PDF, ZIP et images. Tous les fichiers sont traités localement sous le contrôle de l'utilisateur.

## 3. Responsable / développeur

- **Développeur** : Files Tech / Patrice
- **Site internet** : https://www.files-tech.com
- **Contact confidentialité** : contact@files-tech.com
- **Dépôt source** : https://github.com/gitubpatrice/READ-FILES-TECH
- **Licence du code source** : Apache License 2.0

## 4. Données accessibles ou traitées

| Type de donnée                  | Utilisation                                                                                          | Lieu de traitement                  |
| ------------------------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------- |
| Fichiers choisis par l'utilisateur | Lecture, affichage, édition, conversion, partage à la demande de l'utilisateur.                   | Principalement local sur l'appareil. |
| Données techniques réseau       | Partage et email : uniquement sur action de l'utilisateur. **Vérification de mise à jour : automatique au lancement** (voir §6 bis).                 | Service tiers concerné.             |
| Préférences locales             | Fichiers récents, réglages d'affichage, ordre de tri.                                                | Stockage local sur l'appareil.      |

## 5. Absence de publicité, traceurs et analyse

Le développeur déclare que l'application ne contient pas de publicité, de traceur, de mesure d'audience, d'analyse comportementale ou de système de profilage. L'application ne vend pas les données de l'utilisateur.

## 6. Partage et transmission de données

Les fichiers ou contenus ne sont transmis à un tiers que sur action explicite de l'utilisateur (bouton « Partager », export), via l'utilisation volontaire d'un service tiers, ou pour respecter une obligation légale applicable.

### Précisions

- Les rendus HTML/WebView affichent des contenus choisis par l'utilisateur ; rester prudent avec les fichiers de sources non fiables. JavaScript est **désactivé par défaut** pour les fichiers HTML (opt-in via la barre d'outils).
- La vérification de mises à jour interroge l'API GitHub Releases publique (HTTPS, sans authentification, sans cookie). Aucun identifiant utilisateur n'est transmis.

## 6 bis. La seule connexion que l'application ouvre d'elle-même

Ce paragraphe existe parce que la version précédente de ce document laissait
croire que toute activité réseau était déclenchée par l'utilisateur. C'était
inexact, et la précision compte davantage que la rassurance.

**Au lancement de l'application**, sans que l'utilisateur ait rien demandé, une
requête `GET` est émise vers :

```
https://api.github.com/repos/gitubpatrice/read-files-tech/releases/latest
```

- **Ce qui part** : rien d'autre que ce qu'une requête HTTPS anonyme comporte —
  en-tête `Accept`, et l'adresse IP publique de l'appareil, vue par GitHub comme
  par n'importe quel serveur que l'on contacte. Aucun identifiant d'appareil,
  aucun compte, aucun cookie, aucune donnée d'usage, aucun nom de fichier.
- **Ce qui revient** : le numéro de la dernière version publiée et ses notes.
- **À quelle fréquence** : au plus une fois toutes les 12 heures ; le résultat
  est mis en cache localement. Et à la demande depuis l'écran « À propos ».
- **Pourquoi c'est automatique** : l'application est distribuée par sideload,
  hors de tout magasin d'applications. Aucun mécanisme du système ne signale à
  l'utilisateur qu'un correctif de sécurité est disponible. Cette requête est le
  seul canal qui le fasse.
- **Comment s'y soustraire** : refuser l'accès réseau à l'application dans les
  réglages d'Android, ou la laisser hors connexion. L'échec est silencieux et
  sans conséquence — aucune fonctionnalité de lecture, d'édition, d'OCR ou de
  coffre ne dépend du réseau.

Aucun fichier, aucun contenu de document, aucune donnée du coffre n'est
concerné par cette requête, ni par aucune autre émise par l'application.

## 7. Conservation et suppression

Les fichiers restent sous le contrôle de l'utilisateur. Aucun compte propre à l'application n'est créé.

## 8. Sécurité

L'application met en place :

- une `network_security_config` refusant le trafic en clair et les autorités utilisateur ;
- une validation des chemins sur les MethodChannels natifs (Kotlin) ;
- une protection anti zip-slip sur les extractions d'archives ;
- des limites de taille pour prévenir un DoS local.

Voir [SECURITY.md](./SECURITY.md) pour la politique de signalement.

## 9. Permissions Android

Liste établie par `aapt dump permissions` sur l'APK **release publié**, et non
d'après le manifeste source : deux permissions sont ajoutées au moment de la
fusion des manifestes par une dépendance, et n'apparaissent nulle part dans le
code de l'application.

| Permission / accès                  | Raison                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`           | Fonction explorateur : parcourir, lire, éditer tout fichier choisi par l'utilisateur.                  |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | Même fonction, sur les versions d'Android antérieures à `MANAGE_EXTERNAL_STORAGE`. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Affichage et aperçu des fichiers médias choisis par l'utilisateur (Android 13+).                  |
| `CAMERA`                            | Scanner de documents et OCR (optionnel, accordée à la demande au runtime).                              |
| `REQUEST_INSTALL_PACKAGES`          | Installer une APK tapée dans l'explorateur. L'installation elle-même est faite par l'installeur du système, qui demande sa propre confirmation. |
| `INTERNET`, `ACCESS_NETWORK_STATE`  | Vérification de mise à jour via l'API publique GitHub Releases (§6 bis). C'est leur **seul** usage : le transport de télémétrie de Google embarqué avec ML Kit ne peut plus démarrer depuis la v2.15 — voir §9 bis. |

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
   dans `classes.dex` — et `INTERNET` reste déclarée, parce que l'application s'en
   sert elle-même pour la vérification de mise à jour (§6 bis).
2. **Le scanner de documents s'appuie sur les services Google Play.**
   `play-services-mlkit-document-scanner` est un client léger : l'interface de
   numérisation et son modèle vivent dans les services Play et sont téléchargés
   à la demande. Sur un appareil sans services Google Play, le scanner ne
   fonctionne pas ; le reste de l'application, OCR compris, fonctionne
   normalement.

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

## 10. Enfants

L'application n'est pas spécifiquement destinée aux enfants et ne contient aucun mécanisme de publicité comportementale ou de profilage.

## 11. Modifications

Cette politique peut être mise à jour lors de l'évolution de l'application.

## 12. Contact

📧 **contact@files-tech.com**
