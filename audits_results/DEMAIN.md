# Reprise — Read Files Tech

*Mis à jour le 2026-08-11 en fin de journée. Liste courte et autoritaire.*
*Le contexte complet est dans `REPRISE-V2.15.md` ; ici, seulement la suite.*

---

## État

| | |
|---|---|
| Branche | `main`, arbre **propre**, **tout poussé** |
| Commits depuis `c89b820` (v2.14.0) | **68** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **241 tests verts** (20 fichiers) |
| CI GitHub | **verte** |
| Build release | **passe** — 35,6 / 40,7 / 42,9 Mo |
| Version | **2.14.0+21400** — non bumpée, **aucun tag** |
| Appareils | APK à jour sur le **S9 (API 29)** et le **S24 (API 36)** |

**Tout ce qui a été livré le 2026-08-11 a été reconstaté sur les deux
téléphones**, corpus `Téléchargements/rft_test_v216/` (10 fichiers piégés).

---

## 1. Ce qui reste à faire

### 1.1 — Fichiers jamais relus par un tiers

Il en reste quatre, tous de taille modeste :

- `pdf_signature_service.dart`
- `exif_service.dart`
- `image_bounds.dart`
- `csv_safe.dart`

`app_update.dart` a été soumis : **rien à en tirer**, il ne fait que construire
un `UpdateService` de `files_tech_core`. Le code réseau réel — validation de la
réponse, redirections, bornage de taille, comparaison de versions — vit dans le
paquet partagé et **n'a jamais été relu**. C'est le seul code de l'application
qui sort sur le réseau ; le relire suppose d'ouvrir `files_tech_core`, ce qui
engage tout le portefeuille.

Commande, à réutiliser telle quelle :

```
python /c/Users/Pat/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 \
  --prompt <scratchpad>/prompt_audit2.txt --out <sortie>.md <fichiers…>
```

⚠️ **Vérifier que le fichier de sortie existe.** `gemini-3.1-pro-preview` a
rendu un rapport **vide sans message d'erreur** : une relecture absente
ressemble beaucoup à une relecture qui n'a rien trouvé.

### 1.2 — Décision produit en attente

`docxXmlToPlainText` écrit une ligne par paragraphe **même vide**. Signalé le
2026-08-09. C'est le comportement du service, aligné avec l'outil de
conversion : le changer modifierait aussi la sortie de l'outil. **À trancher
comme un choix produit, pas comme un correctif.**

### 1.3 — Décisions en attente de Patrice

- **Bump + tag.** Rien n'est bumpé, aucun tag posé. **Interdit sans demande
  explicite.** 68 commits attendent une version.
- **Syncfusion 34** — à rouvrir **quand `file_picker` 12 sera stable**. La
  montée sera alors gratuite ; aujourd'hui elle coûterait soit une bêta sur le
  sélecteur de fichiers, soit un `dependency_overrides`. Voir `REPRISE` §4.

---

## 2. ✅ Fait le 2026-08-11 — ne pas rouvrir

| Sujet | Ce qui a été corrigé |
|---|---|
| **CI** | Le repli de `reserveFile` **levait** hors Android au lieu de rendre `null`. Vert depuis. |
| **Recherche par contenu** | Rendait **tous les fichiers du téléphone**. Un critère absent était traité comme un critère satisfait. |
| **Recherche — dossiers cachés** | `.thumbnails/` remontait une vignette par photo. |
| **Recherche — dossier illisible** | `Android/data` **interrompait toute la recherche** sur Android 11+. |
| **Recherche — annulation** | Câblée à l'envers : `cancel()` envoyait dans le vide. Deux fuites corrigées. |
| **Corbeille** | Métadonnées lues **sans borne** depuis le stockage partagé ; deux `listSync` bloquants. |
| **Visionneuse HTML** | Un DOCTYPE non fermé faisait poser la CSP **après** la requête sortante. |
| **Visionneuse d'images** | Le diagnostic précis existait mais **n'était pas câblé** sur l'écran d'ouverture. |

Chacun de ces correctifs a été **falsifié** : défaut remis, test vérifié rouge.

---

## 3. Trois règles que ces deux jours ont payées cher

**L'appareil décide.** Les défauts trouvés par le téléphone l'ont été avec
`analyze` à 0 et toute la suite au vert.

**Saboter le correctif pour valider le test.** Le 2026-08-11, un test de
plafond de corbeille est resté **vert après suppression de la borne** : il
passait pour une autre raison que celle qu'on croyait, et ne prouvait rien. Sans
ce geste, il aurait été commité tel quel.

**Vérifier les constats externes avant d'agir.** Sur les deux audits, deux
constats étaient **faux** — `reader_service` prétendument sans garde
anti-bombe, `app_update` prétendument porteur du code réseau. Mais c'est en
allant les vérifier qu'ont été trouvés deux vrais défauts que personne n'avait
signalés. **Le meilleur usage d'une relecture externe n'est pas sa liste : c'est
l'endroit où elle fait regarder.**

**Et le corollaire, appris le 2026-08-11 :** les deux défauts les plus graves de
la journée — la recherche par contenu et le DOCTYPE non fermé — n'ont été
trouvés ni par relecture externe, ni par relecture humaine, mais **en écrivant
des tests hostiles**. Aucun n'était dans une liste d'audit.
