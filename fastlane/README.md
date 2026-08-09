# fastlane/metadata — notes de tenue

Ce dossier suit la convention *Fastlane Supply* : un fichier de changelog par
`versionCode`, dans `metadata/android/<locale>/changelogs/<versionCode>.txt`.

## Ce qu'il faut savoir avant d'y toucher

**Le `versionCode` 21500 n'est pas encore posé.** `pubspec.yaml` est à
`2.14.0+21400` au moment où ces fichiers sont écrits. Le changelog est préparé
pour la version 2.15.0 à venir ; il ne bumpe rien et ne doit pas être lu comme
une version publiée. Si le numéro de version change, **renommer les deux
fichiers**, pas seulement l'un des deux.

**Cap de 500 caractères.** C'est la limite de F-Droid, et elle porte sur les
caractères, pas les octets — un « é » compte pour un. Le fichier français est
actuellement à 498 : toute phrase ajoutée doit en retirer une autre.

```sh
wc -m fastlane/metadata/android/*/changelogs/*.txt
```

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
