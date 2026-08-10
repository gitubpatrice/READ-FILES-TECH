# À faire demain — Read Files Tech

*Écrit le 2026-08-10 en fin de journée. Liste courte et autoritaire.*
*Le contexte complet est dans `REPRISE-V2.15.md` ; ici, seulement la suite.*

---

## État au moment de couper

| | |
|---|---|
| Branche | `main`, arbre **propre**, **tout poussé** |
| Commits depuis `c89b820` (v2.14.0) | **59** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **204 tests verts** (18 fichiers) |
| Build release | **passe** — 35,6 / 40,7 / 42,9 Mo |
| Version | **2.14.0+21400** — non bumpée, **aucun tag** |
| Appareils | APK installé sur le S9 ; le S24 a l'avant-dernière version |

---

## 0. ⚠️ VÉRIFIER QUE LA CI EST VERTE — avant tout le reste

La CI a échoué le 2026-08-10 au soir sur le commit `0eca8cc`, et le correctif
(`b7e…`, dernier commit) **n'a pas pu être vérifié localement**.

**La cause.** La sauvegarde du coffre passe désormais par
`OutputStorageService.reserveFile`, dont le deuxième repli appelle
`getExternalStorageDirectory()`. Cette fonction n'existe **que sur Android** :
ailleurs elle **lève** au lieu de rendre `null`. Cinq tests du coffre sont
tombés.

**Pourquoi c'est invisible en local.** Sous Windows,
`/storage/emulated/0/Files Tech/...` se crée sans difficulté : le chemin
principal réussit, le repli n'est jamais emprunté, et les 204 tests passent.
Sous Linux, le chemin principal échoue et le repli est atteint. **Le correctif
ne peut pas davantage être validé localement, pour la même raison.**

```
gh run list --limit 3
```

Si elle est encore rouge, lire le journal avant toute autre chose : c'est le
seul environnement qui exerce ce chemin.

**La leçon à retenir** : une suite verte sur une seule plateforme ne dit rien
des replis spécifiques à une autre.

---

## 1. Reconstater sur appareil ce qui a été livré en fin de journée

**À faire en premier.** Ces correctifs touchent le coffre, la corbeille et
l'écriture de fichiers. Ils sont couverts par des tests, mais la journée a
montré huit fois qu'un test vert ne dit pas ce qui se passe sur le téléphone.

1. **Coffre** — déverrouiller, importer un fichier, le lire, l'exporter.
2. **Sauvegarde du coffre** — ⋮ → « Sauvegarder le coffre ». Vérifier le chemin
   annoncé dans le bandeau, puis que le fichier est bien dans
   `Files Tech/Sauvegardes coffre/`.
3. **Restaurer une sauvegarde** — sur un coffre qui contient déjà le fichier :
   attendu « Rien restauré : 1 fichier déjà présent » avec un bouton
   **Remplacer** qui tient **7 secondes**. L'actionner, confirmer, obtenir
   « 1 restauré ».
4. **Corbeille** — supprimer plusieurs fichiers d'un coup, en restaurer un,
   en supprimer un définitivement. Vérifier que les autres sont intacts.
5. **EPUB** — en ouvrir un vrai. Puis renommer n'importe quel fichier en
   `.epub` et l'ouvrir : le message doit dire que ce n'est pas une archive,
   pas que `container.xml` manque.

---

## 2. Les quatre points restants de l'audit externe

Réels, vérifiés, moins graves que ce qui a été corrigé. Ordre conseillé :

1. **`trash_service.list()` lit les métadonnées sans borne.** Un JSON énorme
   déposé dans `.RFT_Corbeille/meta/` — sur le stockage **partagé**, donc
   accessible à toute application ayant la permission — ferait geler
   l'ouverture de la corbeille. Et le **mode panique** l'appelle. Un plafond de
   lecture suffit.

2. **E/S synchrones** dans `trash_service.list()` et `_existingTrashRoots()`
   (`listSync`). Gel perceptible sur stockage lent, sur un chemin appelé lui
   aussi par le mode panique.

3. **`duplicate_finder_service` : appels concurrents à `find()` non gérés.**
   Deux lancements laissent des isolates orphelins qui continuent à consommer.

4. **`docxXmlToPlainText` écrit une ligne par paragraphe même vide.** Signalé le
   2026-08-09. C'est le comportement du service, aligné avec l'outil de
   conversion : le changer modifierait aussi la sortie de l'outil. **Décision
   produit**, pas correctif.

---

## 3. Décisions en attente de Patrice

- **Bump + tag.** Rien n'est bumpé, aucun tag posé. **Interdit sans demande
  explicite.** 59 commits attendent une version.
- **Syncfusion 34** — à rouvrir **quand `file_picker` 12 sera stable**. La
  montée sera alors gratuite ; aujourd'hui elle coûterait soit une bêta sur le
  sélecteur de fichiers, soit un `dependency_overrides`. Voir `REPRISE` §4.

---

## 4. Ce qui n'a pas encore été relu par un tiers

Huit fichiers l'ont été le 2026-08-10. Il en reste, dont deux qui portent du
risque :

- `html_viewer_screen.dart` (452 l.) — **WebView + CSP injectée**, et
  `setAllowFileAccess` activé. C'est la plus grosse surface non relue.
- `app_update.dart` — le **seul** code qui sort sur le réseau.
- `global_search_service.dart` (305 l.), `pdf_signature_service.dart`,
  `exif_service.dart`, `image_bounds.dart`, `csv_safe.dart`.

Commande utilisée, à réutiliser telle quelle :

```
python /c/Users/Pat/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 \
  --prompt <scratchpad>/prompt_audit.txt --out <sortie>.md <fichiers…>
```

⚠️ **Vérifier que le fichier de sortie existe.** `gemini-3.1-pro-preview` a
rendu un rapport **vide sans message d'erreur** : une relecture absente
ressemble beaucoup à une relecture qui n'a rien trouvé.

---

## 5. Deux règles que la journée a payées cher

**L'appareil décide.** Huit défauts trouvés en deux jours par le téléphone,
chaque fois avec `analyze` à 0 et toute la suite au vert. Aucun n'a été vu par
une relecture de code.

**Saboter le correctif pour valider le test.** Remettre le défaut et vérifier
que le test rougit. C'est ce geste qui a montré que les neuf tests de la garde
anti-bombe ne testaient pas ce qu'on croyait — ils vérifiaient que l'entrée
était *refusée*, jamais que la mémoire restait *bornée*.

**Et vérifier les constats externes avant d'agir.** Sur l'audit d'aujourd'hui,
un constat était **faux** : GPT affirmait que `reader_service` décompressait
sans garde anti-bombe, alors que `safeEntryBytes` y est appelé partout. Mais
c'est en allant le vérifier qu'est apparu un vrai défaut, que personne n'avait
signalé — la garde ZIP manquante sur ce cinquième site.
