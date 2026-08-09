# Read Files Tech — consolider, corriger, durcir avant la v2.15

Tu travailles sur **Read Files Tech**, `j:\applications\read_files_tech`, dépôt
`gitubpatrice/READ-FILES-TECH`. Flutter/Dart + une couche native Kotlin. Explorateur de fichiers,
lecteur universel (DOCX, ODT, XLSX, CSV, EPUB, HTML, PDF, code, images, archives), scanner de
documents, OCR, **coffre-fort chiffré**, conversion, corbeille, retrait EXIF. Le tout annoncé
« 100 % local ».

Ce n'est pas un audit à blanc. **Un audit existe déjà et n'a jamais été appliqué.**

---

## 0. Le point de départ, à lire avant tout le reste

`audits_results/audit_haut_niveau_20260802.txt` — 579 lignes, 6 agents en lecture seule, produit le
**2026-08-02 sur la v2.14.0**, c'est-à-dire sur le code actuel. Il est **non commité** (untracked)
et **aucune de ses 23 recommandations n'a été appliquée**.

Ses notes : sécurité 8,5/10, qualité 7/10, architecture 6,5/10, cohérence 78/100. Aucun Critical.
Son observation la plus utile, et qui doit guider tout ton travail :

> « les mêmes pièges sont résolus ici et retombés là : patterns résolus dans un écran, réintroduits
> dans un autre. »

**Ta première tâche n'est pas de corriger, c'est de trancher.** Pour chacun des 23 points du plan
§8, dis **CONFIRMÉ** (avec `fichier:ligne` qui le prouve), **RÉFUTÉ** (avec le code qui le
contredit), ou **OBSOLÈTE** (corrigé depuis). Sur un audit comparable mené en août sur Agenda Tech,
**6 constats externes sur ~20 étaient réfutables contre le code** — deux relecteurs différents ont
signalé en ÉLEVÉ un « bug du I turc » qui n'existait pas, parce que `lowercase()` sans argument est
déjà `Locale.ROOT` en Kotlin. **Une relecture qui valide n'est pas une preuve.** Réfuter proprement
un constat a autant de valeur que le corriger.

Ensuite seulement, corrige — en commençant par ce que l'audit classe PRIORITÉ RELEASE.

---

## 1. État réel vérifié le 2026-08-09 — pars de là, ne le redécouvre pas

| | |
|---|---|
| Version | `2.14.0+21400`, tag `v2.14.0`, branche `main` |
| Code | **79** fichiers `.dart`, **21 267** lignes dans `lib/` |
| Tests | **7 fichiers, 592 lignes — soit 2,8 %**, et **zéro test sur le coffre** |
| `flutter analyze` | **0 issue** (après `flutter pub get` — voir le piège ci-dessous) |
| CI | `ci.yml`, `release.yml`, `security.yml`, `claude-review.yml` |
| Arbre de travail | propre, sauf `pubspec.lock` et le rapport d'audit untracked |

⚠️ **Piège de mesure rencontré** : `flutter analyze --no-pub` a d'abord rendu **30 erreurs**
(`Target of URI doesn't exist: package:file_picker`). Ce n'était pas le code — c'était une
résolution périmée. Après `flutter pub get`, zéro. **Ne conclus jamais à un défaut sur une commande
qui n'a pas résolu ses dépendances.**

---

## 2. Ce que l'audit du 2026-08-02 n'a pas couvert, et que je veux couvert

Ces cinq points viennent de mon propre passage sur le dépôt aujourd'hui. Ils ne figurent pas dans
le rapport existant.

### 2.1 `REQUEST_INSTALL_PACKAGES` — la permission la plus lourde du portefeuille

`android/app/src/main/AndroidManifest.xml:39`. **Aucune autre app Files Tech ne la demande.** Un
explorateur de fichiers qui peut déclencher l'installation d'un APK est une surface d'abus
sérieuse : un fichier reçu par messagerie, ouvert d'un tap depuis l'explorateur, peut lancer un
installeur.

- Retrace le chemin **complet** : `file_explorer_screen.dart`, `file_type_helpers.dart`,
  `native_open_service.dart`, `apk_icon.dart`, `file_row.dart`, `home_screen.dart`,
  `rft_picker_screen.dart`, et `MainActivity.kt:256` qui parle d'un « anti faux positif Play ».
- Question à trancher, pas à contourner : **cette permission est-elle nécessaire ?** Un `Intent`
  `ACTION_VIEW` sur un `content://` d'APK délègue l'installation au *package installer* du système,
  qui demande lui-même sa propre autorisation. Si c'est le cas ici, la permission est superflue et
  son retrait est un gain net.
- Si elle reste : y a-t-il un écran de confirmation explicite, et l'utilisateur voit-il ce qu'il
  s'apprête à installer ?

### 2.2 Surface de permissions incohérente avec le reste du portefeuille

Déclarées : `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`,
`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `CAMERA`, `REQUEST_INSTALL_PACKAGES`.

**PDF Tech prend `MANAGE_EXTERNAL_STORAGE` et refuse délibérément `READ_MEDIA_IMAGES`**, en le
documentant dans son `PRIVACY.md` : la sélection d'images passe par le Storage Access Framework,
qui accorde une URI éphémère sans permission runtime. Read Files Tech prend les deux, plus vidéo et
audio.

Pour **chacune** des huit : le code l'utilise-t-il réellement, et le SAF ne suffirait-il pas ?
Chaque permission retirée est un gain permanent. Vérifie aussi le manifeste **fusionné**
(`build/app/intermediates/merged_manifest/release/.../AndroidManifest.xml`), pas seulement la
source — une dépendance peut en ajouter.

### 2.3 Syncfusion : composant **propriétaire**, et ce n'est pas dit

`pubspec.yaml` déclare `syncfusion_flutter_pdf` et `syncfusion_flutter_pdfviewer`. J'ai lu leur
`LICENSE` le 2026-08-09 : Community License **ou** licence commerciale, *« under no circumstances
can you use this product without »* l'une des deux. C'est du propriétaire, dans une app publiée
sous Apache 2.0.

Ce n'est **pas** un conflit de licence : aucune source Syncfusion n'est dans le dépôt, elle est
tirée de pub.dev au build. Mais l'APK distribué embarque du Syncfusion compilé et n'est donc pas,
dans son ensemble, un artefact Apache 2.0. Vérifie que `THIRD_PARTY_NOTICES.md` le dit
explicitement — c'est la correction qui vient d'être faite sur PDF Tech (commit `9dcf032`), et le
même texte s'applique ici. Aucune décision de licence n'est de ton ressort ; signale, ne tranche
pas.

### 2.4 `files_tech_core` en chemin relatif — l'app n'est compilable que sur ce poste

```yaml
files_tech_core:
  path: ../files_tech_core
```

Hors de l'arborescence de développement — un clone, la CI, un buildserver — `pub get` échoue sur
« could not find package files_tech_core ». **Publier les sources d'une application que personne ne
peut compiler est contradictoire.**

Pire, c'est silencieux : un simple `flutter pub get` a fait passer `pubspec.lock` de `0.3.2` à
`0.3.4` **sans aucun changement de `pubspec.yaml`**, parce que le dépôt frère avait bougé sur le
disque. Deux builds du même commit ne produisent pas le même binaire.

Correctif déjà appliqué à `notes_tech` et `pdf_tech` : dépendance **git épinglée à un commit**. Le
paquet est public (`github.com/gitubpatrice/files_tech_core`). Applique le même patron, épingle un
commit précis, et vérifie que `flutter pub get` puis `flutter analyze` passent ensuite.

### 2.5 Google ML Kit contre la promesse « 100 % local »

`google_mlkit_text_recognition` (OCR) et `google_mlkit_document_scanner` (scanner). Le second en
particulier s'appuie sur les Play Services et peut **télécharger un module à l'exécution**.

- L'affirmation « 100 % local » de la description tient-elle pour ces deux fonctions ? Que se
  passe-t-il sur un appareil **sans Play Services** — dégradation propre, ou plantage ?
- `PRIVACY.md` / `PRIVACY.fr.md` décrivent-ils exactement ce qui est embarqué ?
- **Mesure-le sur l'APK, pas sur le `pubspec`.** Sur PDF Tech, la documentation a annoncé ML Kit
  pendant **quatre versions** après son remplacement par Tesseract — y compris dans les documents
  légaux et la fiche de magasin. Après tout changement de bibliothèque :
  `grep -rni <ancien-nom>` sur `*.md`, `fastlane/`, le manifeste et les règles R8.

---

## 3. Les zones que je considère les plus dangereuses, par ordre

### 3.1 Le coffre-fort — sa crypto est bonne, ce sont ses **abords** qui inquiètent

`lib/services/vault_service.dart` (1352 l.) et `lib/screens/vault/vault_screen.dart` (1209 l.).

**Ne pars pas du principe qu'il faut la refaire.** J'ai lu le service : Argon2id auto-calibré avec
repli PBKDF2-HMAC-SHA256 600 000 itérations pour les coffres existants, AES-256-GCM, **AAD = les
octets complets de l'en-tête** (magic, version, params, sel, nonce — donc tout tampering d'en-tête
invalide le tag), sel 16 o, dérivation dans un `Isolate`, canal Kotlin natif pour l'AES-GCM,
zeroize, lockout à double horloge. C'est du travail sérieux. Ta mission est de **vérifier ces
propriétés une par une**, pas de proposer un autre schéma.

Ce qui m'inquiète est autour :

- **Le coffre reste déverrouillé indéfiniment au premier plan** (V-H1 de l'audit) — pas de `lock()`
  à la sortie de l'écran. Confirme, et corrige : `dispose()`, passage en arrière-plan
  (`AppLifecycleState`), délai d'inactivité.
- **Zéro test.** 1352 lignes de crypto sans un seul test, dans une app qui en compte 592 au total.
  L'audit le classe en « LOURD », point 19 : *tests du vault AVANT refonte*. Il a raison, et c'est
  l'ordre à respecter. Les tests d'abord : aller-retour chiffrement/déchiffrement, mauvais mot de
  passe, en-tête altéré (le tag GCM doit refuser), coffre v1 PBKDF2 encore ouvrable après
  l'introduction d'Argon2id, export/import avec mot de passe distinct, compteur de lockout.
- **Panic wipe et corbeille.** La corbeille est neuve en v2.14.0 (`trash_service.dart`, 445 l.).
  Un fichier sorti du coffre puis supprimé atterrit-il dans la corbeille, d'où il serait
  récupérable en clair ? L'audit soulève la question (V-M3), tranche-la par un test.

### 3.2 Les parseurs d'entrées non fiables — le cœur du métier de l'app

Cette application ouvre **des fichiers que l'utilisateur n'a pas écrits**. C'est sa fonction, et
c'est sa surface d'attaque principale.

- **ZIP** (`archive`) : `ZipDecoder().decodeBytes()` en cinq endroits —
  `docx_viewer_screen.dart:82` et `:115`, `reader_service.dart:60`,
  `text_extraction_service.dart:98`, `zip_viewer_screen.dart`. Pour **chacun** : *zip slip*
  (une entrée nommée `../../..` s'écrit-elle hors du dossier cible ?), *zip bomb* (ratio de
  décompression et taille décompressée cumulée plafonnés ?), entrée unique gigantesque, nom
  d'entrée contenant un octet NUL ou un séparateur Windows. L'audit dit le natif « anti-zip-slip » —
  **vérifie que la garde couvre les cinq sites, pas un seul.** Le motif dominant de ce dépôt est
  précisément « résolu ici, retombé là ».
- **XML** dans DOCX/ODT : expansion d'entités, *billion laughs*, entité externe.
- **CSV** (`csv`) : l'injection de formule est-elle neutralisée **en écriture** comme en lecture ?
  Il existe un `test/csv_safe_test.dart` — couvre-t-il l'export autant que l'import ?
- **XLSX** (`excel`) et **HTML** (`html`) sur fichier hostile.
- **Images** (`image`) : dimensions déclarées énormes → OOM avant tout décodage.

### 3.3 La WebView

`lib/screens/viewers/html_viewer_screen.dart`. Elle charge un fichier local par
`_controller.loadFile(widget.path)` (`:154`, `:176`) et expose un bascule JavaScript
(`_jsEnabled ? JavaScriptMode.unrestricted : JavaScriptMode.disabled`, `:97-99`).

- Le `NavigationDelegate` (`:100`) bloque-t-il la navigation **sortante** ? Un HTML local avec JS
  actif et accès `file://` peut lire d'autres fichiers de l'appareil et les exfiltrer par une
  requête sortante. C'est le scénario concret à tester.
- Par défaut, JS est-il **désactivé** ? L'utilisateur comprend-il ce qu'il active ?
- L'état du bascule fuit-il d'un document à l'autre ?
- Le viewer Markdown a le même problème sous une autre forme (V-M4 : `imageBuilder` chargeant du
  `http(s)`) — traite les deux ensemble, pas l'un après l'autre.

### 3.4 Mémoire et perte de données

L'audit signale des lectures intégrales non plafonnées (V-H2 à V-H4 : recherche de contenu, aperçu
explorateur, création de zip) et des **écrasements silencieux** (V-M1/M2 : copie/déplacement par
lot, export du coffre). La perte de données est la seule catégorie qu'un utilisateur ne pardonne
pas. Vérifie, corrige, et **écris un test par correction**.

---

## 4. Comment je veux que tu travailles

**Vérifie sur l'artefact, pas sur le raisonnement.** Une recherche d'octets dans un dex répond
« trouvé » sur un simple descripteur de type orphelin et **ment** ; il faut parser
(`androguard.core.dex.DEX` → `get_classes()`). J'ai commis cette erreur, elle m'a coûté une
conclusion fausse.

**Corrige le défaut entier, pas sa moitié constatée.** C'est le motif qui a produit presque tous
les vrais bugs de ce portefeuille : un correctif traite le cas signalé et laisse le cas jumeau.
Quand tu corriges un site, cherche systématiquement ses frères — les cinq `ZipDecoder` en sont
l'illustration parfaite.

**Méfie-toi d'une correction mesurée sur la population qui l'arrange.** Sur Agenda Tech, le budget
d'expansion des rappels a été refait **cinq fois** ; chaque version intermédiaire était validée sur
le jeu de données qui la flattait, et l'une d'elles armait *zéro* rappel sur un agenda ordinaire.
Mesure toujours sur au moins deux populations opposées.

**Build à chaque étape.** `flutter analyze` doit rester à **0 issue** — c'est l'état actuel, ne le
dégrade pas. `flutter test` doit passer. Ne masque jamais un échec (la CI contient un
`flutter test || echo "No tests yet"` que l'audit signale en M3 — c'est à supprimer, pas à imiter).

**Sur les commentaires qui mentent.** Signale chaque commentaire ou doc qui promet une propriété
que le code ne garantit pas. C'est la deuxième famille de défauts la plus productive ici, juste
après le correctif asymétrique.

---

## 5. Les relectures externes — quand, sur quoi, et avec quelle méfiance

Elles ne sont pas facultatives sur ce dépôt. Sur Agenda Tech, **GPT et Gemini ont trouvé
indépendamment le défaut le plus grave de tout l'audit — et il était dans un correctif que je venais
d'écrire** : un budget partagé qui, au redémarrage, laissait désarmés tous les rappels situés
derrière une série pathologique. Aucune relecture de mon propre code ne l'avait vu.

### Comment les lancer

```bash
python ~/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 \
  --prompt prompts/<le-prompt-cible>.md \
  --out audits_results/ia-externe/rapport-gpt-<sujet>-<date>.md \
  <les fichiers à joindre>
```

Idem avec `--provider gemini`. **Gemini rend souvent `HTTP 503`** (« high demand ») : relancer en
boucle, ça finit par passer — parfois du premier coup, parfois après une trentaine de tentatives.
Ne jamais lire une clé d'API dans la conversation : c'est le script qui les lit.

### Le moment qui compte

Ne les lance **pas** au début, sur du code que tu n'as pas encore touché — l'audit du 2026-08-02 a
déjà fait ce travail. Lance-les **après avoir écrit tes correctifs, sur tes correctifs**. C'est là
qu'elles paient, parce que c'est le seul endroit où personne d'autre n'a regardé.

### Les zones qui les méritent ici

Par ordre de rendement attendu :

1. **Le coffre**, une fois tes tests écrits — joins `vault_service.dart`, `vault_screen.dart` et tes
   nouveaux tests, et demande explicitement : *ces tests peuvent-ils passer alors que la propriété
   qu'ils prétendent vérifier est fausse ?* Sur Agenda Tech, **un de mes tests épinglait un défaut**
   au lieu de le détecter — il affirmait que le comportement fautif était le comportement attendu.
   C'est le pire endroit où se tromper.
2. **Les cinq sites `ZipDecoder`** ensemble, jamais un seul : la question utile est « la garde
   couvre-t-elle les cinq ? », et elle ne se pose qu'en les voyant côte à côte.
3. **La WebView + le viewer Markdown** ensemble, pour la même raison.
4. **Tes correctifs de permissions**, avec le manifeste fusionné en pièce jointe.

### La discipline, qui vaut autant que la relecture

**Chaque constat externe se vérifie contre le code avant d'être corrigé.** Sur ~20 constats
rendus sur Agenda Tech, **6 étaient réfutables** — dont un signalé en ÉLEVÉ par les deux
relecteurs simultanément, et faux. Et à l'inverse, **les deux ont validé une version qui armait
zéro rappel** sur un agenda ordinaire.

Une relecture externe est un générateur d'hypothèses, pas une autorité. Réfute par un test quand
c'est possible : c'est la seule réponse qui ne se re-discute pas. Consigne les réfutations dans ton
rapport au même titre que les corrections — savoir qu'un constat est faux évite de le re-traiter
dans six mois.

---

## 6. Interdits — non négociables

- 🔴 **Ne touche jamais au keystore ni à la signature.** `keystore.properties` et
  `local.properties` sont présents en local : ne pas les lire, ne pas les commiter, ne pas les
  modifier. Tout constat demandant de changer la clé de signature est **faux** : cela romprait le
  chemin de mise à jour de toutes les installations existantes.
- 🔴 **Jamais `git add -A`.** Stage explicitement, fichier par fichier — du travail a déjà été
  écrasé ainsi. Messages de commit via **`git commit -F fichier`**, jamais `-m` : les accents dans
  `-m` effacent des fragments.
- 🔴 **Jamais `git checkout --`** sur un fichier portant du travail non commité.
- 🔴 **Un audit ne doit jamais effacer le travail existant.** Ajoute plutôt que de remplacer. Ne
  modifie pas de code existant sans raison forte et énoncée.
- 🔴 **Ne régénère aucune baseline** pour faire passer un contrôle. Corrige la cause.
- 🔴 **Aucune release, aucun tag, aucun bump de version** sans demande explicite. Un bump se fait
  dans `pubspec.yaml` uniquement, et seulement au moment de la release.
- Ne propose pas d'ajouter une permission. La direction est le retrait.
- Ne signale pas de préférences de style.

---

## 7. Ce que j'attends en retour

Dépose tes rapports dans **`audits_results/`** — et dans `audits_results/ia-externe/` s'ils
viennent d'une relecture extérieure (GPT, Gemini). Les prompts vivent dans `prompts/`, les rapports
d'entrée dans `audits/`.

**D'abord**, le verdict sur les 23 points de `audits_results/audit_haut_niveau_20260802.txt` §8 :
CONFIRMÉ / RÉFUTÉ / OBSOLÈTE, chacun avec `fichier:ligne`.

**Ensuite**, tes propres constats, sur le format : `fichier:ligne` · **scénario concret** (qui fait
quoi, et ce que l'attaquant ou l'utilisateur obtient) · sévérité · **CONFIRMÉ ou PROBABLE**.

**Puis** les corrections, par ordre : sécurité et perte de données d'abord, tests du coffre ensuite,
cohérence et refactos en dernier. Un commit par thème, message explicatif — dis *pourquoi*, pas
seulement *quoi*.

**Enfin**, ce que tu as délibérément laissé de côté et pourquoi. Une liste honnête de ce qui reste
m'est plus utile qu'un rapport qui se déclare complet.

Si un point est sain, **dis-le explicitement**. « Rien à signaler sur X » est une information que
j'utilise.
