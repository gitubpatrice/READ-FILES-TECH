# fastlane/metadata — notes de tenue

Ce dossier suit la convention *Fastlane Supply* : un fichier de changelog par
`versionCode`, dans `metadata/android/<locale>/changelogs/<versionCode>.txt`.

## Ce qu'il faut savoir avant d'y toucher

**Un changelog préparé n'est pas une version publiée.** Un fichier
`<versionCode>.txt` peut exister avant que le tag correspondant ne soit posé —
c'est même le but, cf. la section suivante. Si le numéro de version change avant
le tag, **renommer les deux fichiers**, pas seulement l'un des deux.

État au 2026-09-20 : `pubspec.yaml` est à `2.15.1+21501`, et les changelogs
21500 et 21501 correspondent tous deux à des versions **publiées**. Le prochain
tag demandera donc un `21502.txt` (ou le versionCode retenu) dans les deux
langues, à écrire avant de taguer.

**Cap de 500 caractères.** C'est la limite de F-Droid, et elle porte sur les
caractères, pas les octets — un « é » compte pour un. Comptes mesurés le
2026-09-20 : `fr-FR/21500` = 449, `en-US/21500` = 430, `fr-FR/21501` = 315,
`en-US/21501` = 274. Le plus serré est donc à 51 caractères de la limite.

⚠️ **`wc -m` ne compte PAS les caractères sur ce poste** — c'est ce que cette
page recommandait, et c'est très probablement d'où venait son « 498 » erroné.
Mesuré le 2026-09-20 sous Git Bash : `wc -m` et `wc -c` rendent tous deux **462**
pour `fr-FR/21500.txt`, dont le contenu fait **449** caractères. MSYS ne décode
pas l'UTF-8 tant que `LANG` est vide (`LC_CTYPE=C.UTF-8` seul n'y suffit pas), si
bien que `wc -m` retombe sur un comptage d'octets et sur-évalue de tous les
accents. Sur `short_description.txt`, il affiche **80** pour un fichier de 77 —
soit exactement la limite, ce qui ferait croire à une marge nulle.

Commande qui fait foi, et qui retire le retour à la ligne final (il ne compte
pas pour le store) :

```sh
python -c "import io,sys
for f in sys.argv[1:]:
    print(len(io.open(f, encoding='utf-8').read().rstrip('\n')), f)" \
  fastlane/metadata/android/*/changelogs/*.txt \
  fastlane/metadata/android/*/short_description.txt
```

Ne pas recopier les chiffres ci-dessus de mémoire : ils se périment à chaque
retouche. C'est exactement ce qui est arrivé à cette page, qui annonçait encore
498 caractères et un `pubspec.yaml` en 2.14.0 six versions plus tard.

**Parité FR ↔ EN obligatoire.** Un changelog présent d'un seul côté est un
défaut, pas une traduction en attente.

## Pourquoi ce dossier existe alors que F-Droid n'est pas un objectif

F-Droid n'est pas visé à ce jour — la distribution se fait par GitHub Releases,
et deux obstacles resteraient à lever de toute façon (les composants Syncfusion
sont propriétaires, ML Kit et les services Play ne sont pas libres ; cf.
`THIRD_PARTY_NOTICES.md`).

Ces fichiers servent malgré tout : ils sont la source des notes de la release
GitHub, ils imposent d'écrire le changelog **avant** de taguer plutôt qu'après,
et ils alignent Read Files Tech sur le reste du portefeuille Files Tech. Le jour
où F-Droid redevient un objectif, l'historique des changelogs existe déjà.
