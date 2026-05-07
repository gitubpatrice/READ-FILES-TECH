# Politique de confidentialité — Read Files Tech

**Version du document** : 28 avril 2026
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
| Données techniques réseau       | Fonctions déclenchées par l'utilisateur : partage, email, mise à jour via GitHub Releases.           | Service tiers concerné.             |
| Préférences locales             | Fichiers récents, réglages d'affichage, ordre de tri.                                                | Stockage local sur l'appareil.      |

## 5. Absence de publicité, traceurs et analyse

Le développeur déclare que l'application ne contient pas de publicité, de traceur, de mesure d'audience, d'analyse comportementale ou de système de profilage. L'application ne vend pas les données de l'utilisateur.

## 6. Partage et transmission de données

Les fichiers ou contenus ne sont transmis à un tiers que sur action explicite de l'utilisateur (bouton « Partager », export), via l'utilisation volontaire d'un service tiers, ou pour respecter une obligation légale applicable.

### Précisions

- Les rendus HTML/WebView affichent des contenus choisis par l'utilisateur ; rester prudent avec les fichiers de sources non fiables. JavaScript est **désactivé par défaut** pour les fichiers HTML (opt-in via la barre d'outils).
- La vérification de mises à jour interroge l'API GitHub Releases publique (HTTPS, sans authentification, sans cookie). Aucun identifiant utilisateur n'est transmis.

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

| Permission / accès                  | Raison                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `INTERNET`                          | Vérification de mises à jour via API GitHub Releases.                                                  |
| `MANAGE_EXTERNAL_STORAGE`           | Fonction explorateur : parcourir, lire, éditer tout fichier choisi par l'utilisateur.                  |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Affichage et aperçu des fichiers médias choisis par l'utilisateur (Android 13+).                  |
| `REQUEST_INSTALL_PACKAGES`          | Déclencher l'installateur de paquets Android quand l'utilisateur tape sur un fichier `.apk` (par exemple pour installer l'application compagnon PDF Tech). |
| `CAMERA`                            | Scanner de documents et OCR (optionnel, accordée à la demande au runtime).                              |

## 10. Enfants

L'application n'est pas spécifiquement destinée aux enfants et ne contient aucun mécanisme de publicité comportementale ou de profilage.

## 11. Modifications

Cette politique peut être mise à jour lors de l'évolution de l'application.

## 12. Contact

📧 **contact@files-tech.com**
