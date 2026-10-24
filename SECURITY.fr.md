# Politique de sécurité — Read Files Tech

🇬🇧 English version: [SECURITY.md](./SECURITY.md)

## Versions suivies

Seule la dernière version publiée sur GitHub Releases est maintenue du point de vue de la sécurité.

| Version | Suivie |
| ------- | ------ |
| 3.0.x   | ✅ |
| < 3.0   | ❌ — la 3.0 s'installe par-dessus la 2.x et garde ses données |

## Signaler une vulnérabilité

Merci de **ne pas ouvrir de ticket GitHub public**. À la place :

📧 **contact@files-tech.com**, objet `[SECURITY] Read Files Tech — <courte description>`.

Indiquez une description, les étapes pour la reproduire, son impact, la version concernée (affichée dans
l'application sous À propos) et, si vous en avez une, une piste de correction.

- Accusé de réception : sous 7 jours.
- Première évaluation : sous 30 jours.
- Correction : un défaut critique reçoit un correctif sous 30 jours ; un défaut majeur, la version mineure
  suivante ; un défaut mineur rejoint la liste des tâches.

Merci de ne pas rendre la vulnérabilité publique avant qu'un correctif soit publié et que les utilisateurs
aient eu le temps de mettre à jour — en général 30 jours après la publication du correctif.

## Vérifier un APK

Chaque version porte **quatre** APK signés — trois par architecture et un universel — avec leurs empreintes
SHA-256 dans les notes de version :

```
read-files-tech-arm64-v8a-<version>.apk
read-files-tech-armeabi-v7a-<version>.apk
read-files-tech-x86_64-<version>.apk
read-files-tech-universel-<version>.apk
```

Comparez le fichier téléchargé à l'empreinte publiée pour lui ; si elle diffère, ne l'installez pas :

```bash
sha256sum read-files-tech-arm64-v8a-3.0.0.apk
```

Vérifiez ensuite le certificat de signature, qui est ce qui relie un téléchargement à ce projet, quel que soit
le nom du fichier :

```bash
apksigner verify --print-certs read-files-tech-arm64-v8a-3.0.0.apk
```

Son SHA-256 n'a pas changé depuis les versions 2.x : un APK qui en présente un autre ne vient pas d'ici.

## Périmètre

Dans le périmètre : élévation de privilèges ou contournement d'une permission ; traversée de chemin, zip-slip,
injection par la WebView ; lecture ou écriture hors de ce que l'utilisateur a choisi ; données du coffre
lisibles sans son mot de passe ; composant qu'une autre application peut appeler ; plantage exploitable (déni
de service persistant) ; fuite de données de l'utilisateur hors du téléphone.

Hors périmètre : défaut d'ergonomie sans effet sur la sécurité ; vulnérabilité d'une dépendance déjà signalée
en amont ; attaque qui exige un téléphone rooté ou déjà compromis ; attaque physique sur un téléphone
déverrouillé.

## Comment la 3.0 protège ce qu'elle garde

- **Pas de réseau.** Aucune permission `INTERNET` ni `ACCESS_NETWORK_STATE` (`tools:node="remove"`).
  L'intégration continue lit le manifeste fusionné de la version publiée et l'APK lui-même, et échoue sur toute
  permission hors d'une liste revue.
- **Rien des services de Google.** Ni ML Kit, ni services Play, ni Firebase, ni transport de télémétrie :
  refusés par l'intégration continue dans les dépendances et dans le manifeste. La WebView du système est
  exclue de ses statistiques, et l'initialisation d'EmojiCompat au démarrage, qui demanderait sa police à un
  fournisseur de polices, est retirée.
- **Ce que les autres applications peuvent atteindre.** L'activité de lancement (affinité de tâche vide,
  extras ignorés sauf un raccourci d'une liste fermée) et les trois tuiles des réglages rapides (pour le système
  seul) ; rien d'autre n'est exporté, ce que l'intégration continue vérifie exactement. Le FileProvider n'est
  pas exporté : chaque fichier passe par une garde de chemin, puis est servi pour cette intention seulement, en
  lecture — l'écriture n'est donnée qu'à PDF Tech, après vérification de son certificat de signature.
- **Le coffre.** AES-256-GCM, le nom du fichier lié comme donnée associée ; clé dérivée par Argon2id, calibré sur
  le téléphone ; blocage contre la force brute sur deux horloges, si bien que ni un redémarrage ni une horloge
  reculée ne le lèvent ; verrouillage automatique en arrière-plan, sans activité et pendant la veille (une
  alarme) ; chaque opération travaille sur sa propre copie de la clé et s'arrête si le coffre a été verrouillé
  entre-temps ; écrans protégés de la capture ; copies déchiffrées purgées au verrouillage et à chaque démarrage.
  La réparation unique des fichiers que la 2.15.2 chiffrait sous une clé nulle est reprise.
- **Fichiers hostiles.** La visionneuse HTML ne charge aucune ressource du réseau et n'exécute aucun JavaScript
  (WebViewAssetLoader, `blockNetworkLoads`, une CSP restrictive) ; l'extraction d'un ZIP refuse les noms qui
  sortent de son dossier et borne ce qui est produit, pas ce qui est annoncé ; les PDF sont analysés dans un
  processus à part (`:pdf`) qui n'accepte que les appels de l'application ; les motifs que l'utilisateur saisit
  passent par RE2/J, en temps linéaire ; chaque visionneuse a un plafond de taille.
- **Sauvegardes.** La sauvegarde Android et le transfert d'appareil sont désactivés pour les données de
  l'application.

Limites connues, dites aussi dans la politique de confidentialité : le nom et la taille des fichiers du coffre
ne sont pas chiffrés ; l'historique des éditeurs garde des copies en clair des fichiers modifiés ; la corbeille
et les fichiers créés sont sur le stockage partagé ; l'effacement ne réécrit pas ; le mode panique laisse le
presse-papiers et le dossier `Files Tech`.

## Historique

L'historique des durcissements de la ligne 2.x, version par version, est conservé avec sa dernière version :
[SECURITY.fr.md de la v2.15.3](https://github.com/gitubpatrice/READ-FILES-TECH/blob/v2.15.3/SECURITY.fr.md) et sa
[version anglaise](https://github.com/gitubpatrice/READ-FILES-TECH/blob/v2.15.3/SECURITY.md).
