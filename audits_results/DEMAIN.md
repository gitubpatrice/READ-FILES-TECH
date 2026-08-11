# Reprise — Read Files Tech

*Mis à jour le 2026-08-11 après la publication de v2.15.1.*
*Le contexte complet est dans `REPRISE-V2.15.md` ; ici, seulement la suite.*

---

## État

| | |
|---|---|
| Branche | `main`, arbre **propre**, **tout poussé** |
| Version | **2.15.1+21501** |
| Tags | **v2.15.0** et **v2.15.1** publiés, releases GitHub complètes |
| `flutter analyze` | **0 issue** |
| `flutter test` | **257 tests verts** |
| CI GitHub | **verte** |
| `files_tech_core` | épinglé sur **`9d67344`** |
| Site | files-tech.com synchronisé sur **2.15.1** |

---

## 1. Ce qui reste à faire

### 1.1 — ⚠️ NE JAMAIS publier à la main sur ce dépôt

`.github/workflows/release.yml` **fait toute la release** au push du tag :
build signé, splits + universel, calcul des SHA-256, rédaction des notes,
publication des quatre fichiers. **Pousser le tag suffit.**

Le 2026-08-11, la CI a été doublée à la main sur v2.15.0 puis v2.15.1 :
huit assets au lieu de quatre, issus de deux builds indépendants. Un build
Flutter n'étant pas reproductible octet pour octet, les deux universels
avaient des tailles et des empreintes différentes. La CI ayant écrasé les
notes par les siennes, la table d'intégrité ne listait que **ses** quatre
APK : les autres étaient téléchargeables **sans empreinte publiée**.

Les huit assets surnuméraires ont été supprimés. Chaque release porte
désormais quatre fichiers, et **chaque empreinte correspond à sa table**.

Nom canonique :
`read_files_tech-v<version>-{arm64-v8a,armeabi-v7a,x86_64,universel}.apk`.

**Pour vérifier après un tag** — interroger la release, jamais se fier à ce
qu'on croit avoir publié :

```
gh api repos/gitubpatrice/READ-FILES-TECH/releases/tags/<tag>   --jq '.assets[] | "\(.name)  \(.digest)"'
```

### 1.1 bis — VirusTotal

À jour : `read-files-tech.php` affiche **RFT 2.15.1 — 0/68** sur l'empreinte
`84cca7be…`, celle de l'universel de la CI, donc celle qui figure dans les
notes de release.

**Ne jamais écrire un ratio sans l'avoir lu.** La page VirusTotal est rendue
côté client et ne peut pas être interrogée à distance : demander le résultat
à Patrice. Attention aussi à « Reanalyze », qui relance l'entrée existante
sans téléverser — le hash reste alors celui de l'ancien fichier.

### 1.2 — PDF Tech attend une release, et ne peut pas être construite ici

Trois commits depuis `v1.13.5`, dont **sa propre copie du code de mise à jour**
(`SecureUpdateService`), qui portait les quatre mêmes défauts que le paquet
partagé — c'est le seul changement visible pour un utilisateur.

**Le build release échoue**, et c'est antérieur à ces commits :

```
Release signing credentials missing. Set PDFTECH_KEY_ALIAS,
PDFTECH_KEY_PASSWORD, PDFTECH_STORE_FILE and PDFTECH_STORE_PASSWORD
(android/app/build.gradle.kts:109)
```

Sans ces variables d'environnement, ni build ni signature. **Ne pas y toucher
autrement.**

### 1.3 — Décision produit en attente

`docxXmlToPlainText` écrit une ligne par paragraphe **même vide**. Signalé le
2026-08-09. C'est le comportement du service, aligné sur l'outil de conversion :
le changer modifierait aussi la sortie de l'outil. **À trancher comme un choix
produit, pas comme un correctif.**

---

## 2. ✅ Fait le 2026-08-11 — ne pas rouvrir

### Dans l'application

| Sujet | Ce qui a été corrigé |
|---|---|
| **Recherche par contenu** | Rendait **tous les fichiers du téléphone**. Un critère absent était traité comme un critère satisfait. |
| **Recherche** | Dossiers cachés traversés ; un dossier illisible interrompait tout ; annulation câblée à l'envers ; deux fuites. |
| **Corbeille** | Métadonnées lues sans borne ; deux `listSync` bloquants. |
| **Visionneuse HTML** | Un DOCTYPE non fermé faisait poser la CSP **après** la requête sortante. |
| **Visionneuse d'images** | Le diagnostic précis existait mais **n'était pas câblé** sur l'écran d'ouverture. |
| **Garde d'images** | Seuil WebP à 30 octets alors que le lecteur VP8L en lit 25 : un fichier de 25 à 29 octets n'était **jamais inspecté**. |
| **Plafond en pixels** | 12000×12000 franchissait les deux bornes de côté sans les dépasser : 576 Mo de tampon. |
| **Sorties temporaires** | Noms déterministes : deux fichiers de même nom s'écrasaient silencieusement. |
| **`ExifService.inspect`** | Rendait `{}` sur échec — « pas de métadonnées » alors que rien n'avait été lu. |
| **CI** | Le repli de `reserveFile` **levait** hors Android au lieu de rendre `null`. |

### Dans `files_tech_core` — le seul code réseau du portefeuille

Quatre défauts, sur un fichier qui **n'avait aucun test** :

- une version locale suffixée faisait proposer une **rétrogradation** comme une
  mise à jour ;
- le cache était marqué **avant** le décodage JSON : un « 200 » avec une page
  HTML (portail captif Wi-Fi) bloquait tout contrôle pendant douze heures ;
- la réponse n'était bornée par rien ;
- les redirections étaient suivies sans revalider hôte ni schéma.

Puis un **cinquième, introduit en corrigeant les quatre premiers** : le délai ne
couvrait plus la lecture du corps. C'est l'objet de la v2.15.1.

### Portefeuille — qui portait ce code

| App | Verdict |
|---|---|
| Read Files Tech, Pass Tech, Notes Tech, PDF Tech | **corrigées** |
| PDF Tech | avait en plus sa **propre copie** — corrigée séparément |
| SMS Tech, Agenda Tech, App Manager Tech | **Kotlin natif** — hors sujet |
| AI Tech, Health Tech | Flutter, mais **aucun** code de mise à jour |

Health Tech n'a qu'un **lien cliquable** vers la page des releases.

---

## 3. Quatre règles que ces trois jours ont payées cher

**L'appareil décide.** Les défauts trouvés par le téléphone l'ont été avec
`analyze` à 0 et toute la suite au vert.

**Saboter le correctif pour valider le test.** Un test de plafond de corbeille
est resté **vert après suppression de la borne** : il passait pour une autre
raison que celle qu'on croyait.

**Réparer le site signalé ne suffit pas — il faut réparer tous ses jumeaux.**
Trois fois en deux jours : le seuil WebP laissé en place après correction du
GIF ; PDF Tech qui avait sa propre copie du code corrigé dans le paquet ; et
la régression du délai, écrite **deux fois**, dans les deux fichiers.

**Faire relire les correctifs, pas seulement les défauts.** Les deux défauts les
plus graves de la journée — la recherche par contenu et le DOCTYPE non fermé —
n'ont été trouvés ni par relecture externe ni par relecture humaine, mais **en
écrivant des tests hostiles**. Et la régression du délai n'a été trouvée qu'en
faisant relire le **correctif lui-même** : ni `analyze` ni les 257 tests ne
pouvaient la voir, puisque le code se comportait normalement face à un serveur
normal.
