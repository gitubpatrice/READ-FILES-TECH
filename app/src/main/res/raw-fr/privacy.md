# Politique de confidentialité — Read Files Tech

_Dernière mise à jour : 26 octobre 2026 (version 3.0.1)_

## En une phrase

Read Files Tech ne collecte, ne transmet et ne conserve aucune donnée sur un serveur : l'application n'a pas la permission d'accéder à Internet, et vos fichiers ne quittent votre téléphone que lorsque vous les confiez vous-même à une autre application.

## Qui est responsable

Read Files Tech est publiée par Patrice Haltaya (Files Tech), à titre personnel. Contact : **contact@files-tech.com**. Le code source est public : https://github.com/gitubpatrice/READ-FILES-TECH.

Le développeur ne reçoit aucune donnée de l'application : il n'a donc rien à conserver, à consulter ni à effacer vous concernant. Seul un e-mail que vous choisissez de lui écrire lui parvient — « Signaler un bug » y ajoute les versions de l'application et d'Android. Cet e-mail sert uniquement à vous répondre ; il est gardé le temps de l'échange, puis effacé. Vous pouvez en demander l'accès ou l'effacement à la même adresse, et saisir la CNIL (www.cnil.fr) si vous estimez que vos droits ne sont pas respectés.

## Ce que l'application ne fait pas

- Aucune publicité, aucun traceur, aucune mesure d'audience, aucun rapport de plantage envoyé.
- Aucun compte, aucune inscription.
- **Aucune permission Internet** : `INTERNET` et `ACCESS_NETWORK_STATE` sont retirées du manifeste de l'application, et sa chaîne de construction refuse toute version qui en porterait une. L'application ne peut joindre aucun serveur.
- **Aucun service Google** : ni services Google Play, ni ML Kit, ni Firebase. La reconnaissance de texte (OCR) se fait sur le téléphone, avec Tesseract. La WebView qui affiche les pages HTML est réglée pour ne pas envoyer de statistiques d'usage à son éditeur, et l'application ne demande pas sa police d'émojis à un service extérieur.
- L'application ne dresse pas la liste de vos applications. Elle vérifie seulement si quatre applications sont installées, pour vous proposer de leur envoyer un fichier : PDF Tech, kDrive, Proton Drive et Google Drive.

## Ce que l'application lit, et pourquoi

Read Files Tech est un explorateur et un lecteur de fichiers. Avec votre accord — « Accès à tous les fichiers » à partir d'Android 11, l'autorisation de stockage avant —, elle peut lire et modifier tous les fichiers du stockage partagé du téléphone, carte SD comprise. Elle ne le fait que pour ce que vous lui demandez : parcourir un dossier, ouvrir un fichier, le convertir, chercher, trouver les doublons… Elle ne confie jamais à une autre application un fichier des dossiers `Android/data` ou `Android/obb` des autres applications.

## Ce qui est conservé sur votre téléphone

- **Fichiers récents (chemin, nom, taille, date, favori), fichiers retirés de cette liste, thème, dossier de sortie, partage automatique** — préférences privées de l'application, en clair.
- **Réglages du coffre (sel, paramètres de dérivation de la clé), compteurs d'échecs** — préférences privées de l'application, en clair ; aucun mot de passe.
- **Historique des éditeurs : les 10 dernières versions de chaque fichier modifié dans l'éditeur de code ou l'éditeur CSV, avec son chemin** — stockage privé de l'application, **en clair**.
- **Fichiers du coffre** — stockage privé de l'application, chiffrés (voir plus bas).
- **Modèles de l'OCR** — stockage privé de l'application, fournis avec l'application.
- **Copies temporaires : fichier du coffre ouvert ou partagé, PDF protégé déverrouillé, élément d'archive partagé, photos du scanner** — cache privé de l'application, en clair ; effacées quand elles ne servent plus ou, au plus tard, au démarrage suivant.
- **Fichiers créés par les outils : scans, conversions, compressions, images sans EXIF, textes de l'OCR, sauvegardes du coffre, extractions** — dossier `Files Tech` du stockage partagé, ou celui que vous choisissez dans les Réglages, en clair, sauf les sauvegardes du coffre.
- **Corbeille : les fichiers supprimés et leur description (nom, chemin d'origine, taille, date)** — dossier caché `.RFT_Corbeille` à la racine de chaque volume, en clair ; **jamais vidée automatiquement**.

Les fichiers du stockage partagé — le dossier `Files Tech`, la corbeille — sont lisibles par toute autre application à laquelle vous avez donné accès à vos fichiers.

Si le dossier des fichiers créés refuse l'écriture (« Accès à tous les fichiers » refusé, carte retirée), l'application écrit dans son propre dossier du stockage partagé (`Android/data/…/files/Files Tech`), puis dans son stockage privé ; les Réglages le signalent, et le dialogue d'une sauvegarde du coffre le dit. Ces dossiers sont effacés avec l'application, et le mode panique n'efface pas ce qu'ils contiennent.

## Le coffre

- Chaque fichier est chiffré en **AES-256-GCM**, avec une clé dérivée de votre mot de passe par **Argon2id**. Un coffre créé avec une version plus ancienne garde la dérivation de sa création (PBKDF2, ou un Argon2id plus léger). Le mot de passe n'est jamais enregistré.
- **Ne sont pas chiffrés** : le **nom** des fichiers du coffre, leur **taille**, leur nombre et leurs dates, ainsi que les réglages de dérivation de la clé.
- Le coffre se verrouille 30 secondes après que vous avez quitté l'application — téléphone en veille profonde, à son réveil, et toujours avant que le coffre ne réapparaisse —, après 3 minutes sans toucher l'écran, et quand vous quittez son écran. Après 5 mots de passe faux, chaque essai attend plus longtemps, jusqu'à 30 minutes.
- Les écrans du coffre, et les fichiers du coffre ouverts dans l'application, ne peuvent pas être capturés et n'apparaissent pas dans l'aperçu des applications récentes.
- Une **sauvegarde** `.rftvault` est chiffrée en entier, noms compris, sous un mot de passe de sauvegarde distinct ; elle est écrite dans le dossier des sauvegardes du coffre, que le dialogue de la sauvegarde affiche avant : sur le stockage partagé ou, si le dossier de sortie refuse l'écriture, dans un dossier de l'application, effacé avec elle.
- **Partager (déchiffré)** crée une copie en clair, temporaire, effacée au verrouillage et au démarrage suivant. **Exporter** écrit une copie en clair, permanente, dans le dossier que vous choisissez. Ce que fait l'application qui reçoit un fichier ne dépend plus de Read Files Tech.

## Quand quelque chose quitte votre téléphone

Toujours sur une action de votre part, et vers l'application que vous choisissez :

- **Partager**, **Ouvrir avec** : le fichier est confié à l'application choisie, en lecture seule. Android lui laisse lire
  ce fichier, sous ce nom, tant que son écran reste ouvert : si vous le modifiez entre-temps, elle peut en lire la
  nouvelle version.
- **Envoyer** vers kDrive, Google Drive ou Proton Drive, s'ils sont installés.
- **Éditer dans PDF Tech** : l'accès en écriture n'est donné que si l'application installée porte bien la signature de PDF Tech.
- **Partager automatiquement après création**, si vous activez ce réglage (désactivé par défaut).
- **Prendre une photo** pour le scanner ou l'OCR : l'application d'appareil photo du téléphone écrit la photo dans un fichier que Read Files Tech lui ouvre pour cela seulement.
- **Liens** — page des versions, site, e-mail — : ouverts dans votre navigateur ou votre application d'e-mail, qui se connectent eux-mêmes. « Signaler un bug » prépare un e-mail que vous relisez et envoyez vous-même.
- **Liens d'un document** : un lien que vous touchez dans une page HTML ou un fichier Markdown s'ouvre dans votre navigateur ou, pour une adresse e-mail, dans votre application d'e-mail, qui se connectent eux-mêmes ; une page HTML montre d'abord l'adresse et demande confirmation.

La visionneuse HTML affiche une page sans JavaScript et sans aucune connexion : seuls les fichiers du dossier de la page sont chargés.

## Mode panique

Depuis les Réglages, après confirmation, le mode panique efface immédiatement : le coffre et ses réglages, toutes les préférences (fichiers récents compris), tout le cache, l'historique des éditeurs, les anciennes extractions et **la corbeille** sur tous les volumes. Il n'efface pas : vos fichiers, le dossier `Files Tech` (sauvegardes du coffre comprises), les fichiers exportés du coffre, les copies déjà confiées à d'autres applications, ni le presse-papiers. L'effacement supprime les fichiers sans réécrire leur contenu.

## Sauvegarde Android

La sauvegarde d'Android — dans le cloud ou d'un téléphone à l'autre — est désactivée pour les données de l'application. Les fichiers du stockage partagé n'en relèvent pas : un outil qui copie votre stockage peut les emporter.

## Permissions Android

| Permission | Pourquoi |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11 et plus) | L'accès à tous les fichiers : l'explorateur |
| `READ_EXTERNAL_STORAGE` (jusqu'à Android 12), `WRITE_EXTERNAL_STORAGE` (jusqu'à Android 10) | La même chose sur les versions plus anciennes |
| `REQUEST_INSTALL_PACKAGES` | Confier un APK touché dans l'explorateur à l'installateur d'Android, qui demande sa propre confirmation |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Ajoutée par une bibliothèque d'Android, interne à l'application |

Ni appareil photo, ni localisation, ni contacts, ni micro, ni notifications, ni Internet.

## Journal du système

En cas d'erreur d'une opération, l'application écrit une seule ligne dans le journal du système : le type de l'erreur, sans nom de fichier ni contenu. La bibliothèque qui lit les PDF et la WebView du système peuvent y ajouter leurs propres avertissements, sans le contenu des pages, et Android y consigne un éventuel plantage de l'application ; l'application n'envoie ce journal nulle part.

## Données des versions précédentes

La version 3.0.0 reprend en place les données des versions 2.x — préférences, coffre, corbeille, historique — : rien n'est envoyé ni recopié ailleurs, sauf les anciennes extractions, que l'application propose une fois de déplacer dans le dossier `Extractions` de vos fichiers créés, ou d'effacer : rien n'est déplacé ni effacé sans votre accord.

## Vos droits

Vos données sont sur votre téléphone, sous votre contrôle : vous pouvez les effacer depuis l'application (fichiers, corbeille, coffre, mode panique) ou en la désinstallant. La désinstallation efface les données privées de l'application, mais pas le dossier `Files Tech` ni la corbeille, qui sont sur le stockage partagé. Pour toute question : **contact@files-tech.com**.

## Enfants

L'application ne s'adresse pas spécialement aux enfants ; elle ne contient ni publicité ni profilage.

## Modifications

Cette politique peut évoluer avec l'application ; la date en tête indique sa version.

## Langue

Cette politique a été rédigée en français ; les versions dans d'autres langues en sont des traductions. En cas de différence, **la version française fait foi**.

---

Read Files Tech fait partie de la suite **Files Tech**, publiée par **Patrice Haltaya**.
