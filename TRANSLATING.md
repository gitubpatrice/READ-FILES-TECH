# Translating Read Files Tech

Read Files Tech ships in **English** (the source), **French**, **German**, **Italian** and **Spanish**.
English is the reference: a translation says what the English says, no more, no less.

## Where the texts live

- The app: `app/src/main/res/values/strings.xml` (English) and `values-fr/`, `values-de/`, `values-it/`,
  `values-es/`.
- The text recognition (OCR) reads the same five languages; its models and word lists are listed in one
  table, `OcrLanguages` (`app/src/main/java/.../data/ocr/OcrLanguages.kt`).

No text the user reads is built in Kotlin: view models emit a `UiText` (a resource id and its arguments)
that the screen resolves. A new message is a new string resource, in every language.

## Adding a language

The translated folders **are** the list of languages. `app/build.gradle.kts` reads every `values-XX/`
that carries a `strings.xml`, and from that one list:

- keeps the language in the APK (`localeFilters`: a language missing there would be stripped from the
  APK without an error, and the app would stay in English);
- offers it in Android 13's per-app language picker (Settings → Apps → Read Files Tech → Language, also
  reachable from the app's own Settings), whose list the Android Gradle Plugin writes from the same
  resources (`generateLocaleConfig`).

So adding a language is:

1. `app/src/main/res/values-XX/strings.xml`, with every key of the English file except those marked
   `translatable="false"` (product name, joiners such as `%1$s · %2$s`, format names). The folder name is
   the bare language code (`values-pt`, not `values-pt-rBR`): the build reads only that form, and the
   check below refuses the other.
2. The plural categories of the language in `CLDR_QUANTITIES`, in `tools/check-translations.py`: the check
   fails on a language it does not know — and on a language of that table whose folder is gone, so removing a
   language also takes these two gestures.
3. If the text recognition should read it too: one entry in `OcrLanguages` and its model in `assets/`.
4. Excel's words for TRUE and FALSE in that language, in `EXCEL_BOOLEANS`
   (`app/src/main/java/.../data/viewer/sheet/CellText.kt`): without them, the spreadsheet viewer shows the English
   ones. Dates and the order of names follow the language by themselves (the system's short date, a `Collator`).

## Rules that are not style preferences

- **Format arguments** (`%1$s`, `%2$d`, `%,d`, `%%`): the same set as English, flags included, key by key.
  Their order may change — that is what `1$` / `2$` are for. A lost or invented argument crashes the screen.
  In a text that has arguments, a percent sign is written `%%`, French space included (`%1$d %%`): a lone
  `%` crashes it too.
- **Escaping**: an apostrophe is `\'`, a straight double quote is `\"` (or use the typographic quotes of
  the language, which need no escaping). An unescaped straight double quote is silently removed from the
  text.
- **Plurals**: every quantity the language requires — `one`/`other` in English and German,
  `one`/`many`/`other` in French, Italian and Spanish (`many` is the form for millions).
- **Register**: formal — *vous*, *Sie*, *Lei*, *usted* — as in the other Files Tech apps.
- **Android's own words**: when a text names a screen or a setting of Android, use the exact label Android
  shows in that language. Measured on Android 14 (AOSP; a manufacturer may word them differently):

  | Language | "All files access" screen | Its switch | Per-app language page |
  |---|---|---|---|
  | English | All files access | Allow access to manage all files | App language |
  | French | Accès à tous les fichiers | Autoriser l'accès pour gérer tous les fichiers | Langue de l'appli |
  | German | Zugriff auf alle Dateien | Zugriff zum Verwalten aller Dateien zulassen | App-Sprache |
  | Italian | Accesso a tutti i file | Consenti l'accesso per gestire tutti i file | Lingua dell'app |
  | Spanish | Acceso a todos los archivos | Dar acceso para gestionar todos los archivos | Idioma de la aplicación |

  | Language | "Install unknown apps" screen | Its switch |
  |---|---|---|
  | English | Install unknown apps | Allow from this source |
  | French | Installation d'applis inconnues | Autoriser depuis cette source |
  | German | Installieren unbekannter Apps | Dieser Quelle vertrauen |
  | Italian | Installa app sconosciute | Consenti da questa origine |
  | Spanish | Instalar aplicaciones desconocidas | Autorizar de esta fuente |
- **Do not translate**: Read Files Tech, Files Tech, PDF Tech and the other app names, kDrive, Proton
  Drive, Google Drive, Tesseract, file formats and extensions.

## Glossary — terms that must not drift

| English | French | German | Italian | Spanish |
|---|---|---|---|---|
| Vault | Coffre | Tresor | Cassaforte | Caja fuerte |
| Trash | Corbeille | Papierkorb | Cestino | Papelera |
| Panic mode | Mode panique | Panikmodus | Modalità panico | Modo pánico |
| Backup | Sauvegarde | Sicherung | Backup | Copia de seguridad |
| Restore | Restaurer | Wiederherstellen | Ripristinare | Restaurar |
| Lock / unlock | Verrouiller / déverrouiller | Sperren / entsperren | Bloccare / sbloccare | Bloquear / desbloquear |
| Encrypt / decrypt | Chiffrer / déchiffrer | Verschlüsseln / entschlüsseln | Cifrare / decifrare | Cifrar / descifrar |

The vault's texts deserve the most care: locking, the password, the backup and its restore, deleting the
originals after an import, and the panic, which erases. A translation must never make one of them sound
safer than the English does.

## Continuous integration

`tools/check-translations.py` runs in the CI gate, after its negative control
(`tools/check-translations-negative-control.py`). It fails on a missing or extra key, a changed set of
format arguments, a lone `%` in a formatted text, a missing plural category or a count lost from a plural, an
English sentence left untranslated, an unescaped quote, a language folder the build would not ship or that
disappeared, or a change to the derivation above. The languages the APK actually carries, and those its
language picker offers, are read from the release APK itself (`tools/check-release-apks.py`). Run both before a
pull request:

```bash
python3 tools/check-translations-negative-control.py
python3 tools/check-translations.py
```
