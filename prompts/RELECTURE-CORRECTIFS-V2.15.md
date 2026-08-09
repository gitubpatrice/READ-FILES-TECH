# Relecture adverse des correctifs Read Files Tech v2.15

Tu relis des **correctifs qui viennent d'être écrits**, pas du code établi. Personne d'autre ne les
a regardés. C'est le seul endroit où une relecture externe paie vraiment.

Contexte : Read Files Tech, application Flutter/Dart Android (explorateur de fichiers, viewers,
OCR, coffre-fort chiffré AES-256-GCM + Argon2id). Annoncée « 100 % local ». Distribuée hors Play
Store. Le code de production est en français, commentaires compris.

## Ce que je te demande, dans l'ordre

1. **Les tests peuvent-ils passer alors que la propriété qu'ils prétendent vérifier est fausse ?**
   C'est la question la plus importante. J'ai déjà eu un test qui *épinglait* un défaut au lieu de
   le détecter : il affirmait que le comportement fautif était le comportement attendu. Cherche
   activement ce cas. Pour chaque test, demande-toi ce qu'il resterait vert à cacher.

2. **Les correctifs sont-ils complets, ou ont-ils traité le cas signalé en laissant le jumeau ?**
   Le motif dominant de ce dépôt est « résolu ici, retombé là ». Si un correctif touche un site,
   cherche ses frères — même helper, même pattern, même famille d'appels.

3. **Un correctif introduit-il une régression fonctionnelle ?** Notamment : le verrouillage du
   coffre sur inactivité, la garde de partage (`withShare`) qui suspend une purge, la CSP injectée
   dans la WebView qui change le mode de chargement du document (`loadFile` → `loadHtmlString`),
   et le renommage automatique en cas d'homonyme lors d'une copie.

4. **Concurrence et cycles de vie** : compteurs statiques, timers, `setState` après `dispose`,
   ordre des `await`, réentrance.

## Règles de sortie — non négociables

- **Chaque constat doit citer `fichier:ligne` et décrire un scénario concret** : qui fait quoi, et
  ce que l'attaquant ou l'utilisateur obtient. Un constat sans scénario exploitable n'en est pas un.
- **Classe chaque constat CONFIRMÉ (tu peux le prouver sur le code fourni) ou HYPOTHÈSE.** Sur un
  audit comparable, 6 constats externes sur ~20 étaient réfutables contre le code — dont un signalé
  en ÉLEVÉ par deux relecteurs simultanément, et faux. Ne gonfle pas la liste.
- **Ne signale pas de préférences de style**, ni de renommages, ni d'architecture « idéale ».
- **Ne propose jamais d'ajouter une permission Android.** La direction est le retrait.
- Si une section est saine, **dis-le explicitement** : « rien à signaler sur X » est une information
  utile.
- Sévérité : CRITIQUE / ÉLEVÉ / MOYEN / FAIBLE. Justifie CRITIQUE et ÉLEVÉ par l'impact réel.

## Faits établis, à ne pas re-découvrir ni contredire sans preuve

- L'APK release **porte** les permissions `INTERNET` et `ACCESS_NETWORK_STATE`, injectées par
  `com.google.android.datatransport:transport-backend-cct:2.3.3` (télémétrie ML Kit). Elles ne
  figurent pas dans le manifeste source. Vérifié à l'`aapt dump permissions` sur l'APK publié.
  Donc toute fuite réseau est **atteignable** ; ne conclus pas l'inverse.
- `share_plus` 10.1.4 recopie tout fichier partagé dans `<cache>/share_plus/` et n'efface cette
  copie qu'au **début du partage suivant** (`Share.kt:29`, `:250`, `clearShareCacheFolder`).
- `shouldOverrideUrlLoading` renvoie `false` pour les sous-frames dans
  `webview_flutter_android` (`WebViewClientProxyApi.java:78`) : le `NavigationDelegate` ne peut pas
  bloquer un `<iframe>`.
- `webview_flutter_android` 4.13.0 n'expose ni `shouldInterceptRequest` ni `setBlockNetworkLoads`.
- Le coffre : Argon2id auto-calibré, repli PBKDF2-HMAC-SHA256 600 000 itérations pour les coffres
  existants, AES-256-GCM, AAD = `"rft-vault-v2|" + nom de fichier` pour les fichiers, AAD = octets
  complets de l'en-tête pour le format de sauvegarde `.rftvault` v2, dérivation en `Isolate`,
  canal Kotlin natif au-delà de 5 Mo, lockout à double horloge (wall-clock + monotone).

Les sources des correctifs suivent.
