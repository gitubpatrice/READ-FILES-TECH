# Third-Party Notices — Read Files Tech

Read Files Tech's **own source code** is published under the
[Apache License 2.0](./LICENSE).

The application also depends on third-party components. Each one remains subject to its own
license, and **not all of them are open source** — see the Syncfusion notice below. License
notices must be retained in accordance with the obligations of their respective authors.

## ⚠️ Non-free component — Syncfusion

| Package | Version | License |
| --- | --- | --- |
| `syncfusion_flutter_pdf` | 33.2.13 | Syncfusion Essential Studio® EULA |
| `syncfusion_flutter_pdfviewer` | 33.2.13 | Syncfusion Essential Studio® EULA |

Syncfusion components are **proprietary**. They are available either under the Syncfusion
Community License Program or under a commercial license, and may not be used under any other
terms. The full terms are at <https://www.syncfusion.com/content/downloads/syncfusion_license.pdf>.

No Syncfusion source code is contained in this repository: the packages are fetched from
pub.dev at build time. Compiled Syncfusion code **is** embedded in the distributed APK, which is
therefore not, as a whole, an Apache 2.0 artifact.

> **Status as of 2026-08-09: knowingly deferred, not overlooked.** Read Files Tech does not hold a
> registered Syncfusion Community License. Registration is not automatic — using the Community
> License in an open source project requires a prior application to Syncfusion — and the decision
> to postpone it was taken deliberately.
>
> What that means concretely, stated plainly rather than softened: the APK embeds compiled
> proprietary code whose licence has not been formally secured. This notice reports the situation;
> it does not resolve it, and nothing in this repository resolves it. It should be settled before
> any distribution channel that reviews licensing (an F-Droid submission would be the obvious
> trigger), or by replacing the two packages.

## Native libraries actually present in the APK

Verified by unpacking the published release APK, not inferred from `pubspec.yaml`:

| Library | Origin | Note |
| --- | --- | --- |
| `libmlkit_google_ocr_pipeline.so` + `assets/mlkit-google-ocr-models` | Google ML Kit | OCR model bundled — runs fully offline. |
| `play-services-mlkit-document-scanner` | Google Play services | Thin client: the scanner UI and model are downloaded on demand and require Play services. |
| `transport-backend-cct`, `transport-runtime`, `transport-api` | Google | Telemetry transport pulled in by ML Kit. It contributed `INTERNET` and `ACCESS_NETWORK_STATE` to the merged manifest until v2.15 declared them explicitly in the source manifest — the app uses them itself for the update check. See `PRIVACY.md` §6 bis and §9 bis. |

## Direct Flutter / Dart dependencies

(Syncfusion excepted — see the dedicated notice above.)

Versions are those declared in `pubspec.yaml` at the time of writing.

| #  | Package                          | Version    | License (typical)        | Repository                                                          |
| -- | -------------------------------- | ---------- | ------------------------ | ------------------------------------------------------------------- |
| 1  | `cupertino_icons`                | ^1.0.8     | MIT                      | https://github.com/flutter/packages                                 |
| 2  | `file_picker`                    | ^11.0.0    | MIT                      | https://github.com/miguelpruivo/flutter_file_picker                 |
| 3  | `path_provider`                  | ^2.1.4     | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 4  | `permission_handler`             | ^12.0.1    | MIT                      | https://github.com/baseflow/flutter-permission-handler              |
| 5  | `share_plus`                     | ^10.0.3    | BSD-3-Clause             | https://github.com/fluttercommunity/plus_plugins                    |
| 6  | `shared_preferences`             | ^2.3.2     | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 7  | `excel`                          | ^4.0.6     | MIT                      | https://github.com/justkawal/excel                                  |
| 8  | `csv`                            | ^8.0.0     | MIT                      | https://github.com/close2/csv                                       |
| 9  | `archive`                        | ^3.6.1     | Apache-2.0 / MIT         | https://github.com/brendan-duncan/archive                           |
| 10 | `webview_flutter`                | ^4.10.0    | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 11 | `flutter_highlight`              | ^0.7.0     | MIT                      | https://github.com/git-touch/highlight                              |
| 12 | `flutter_colorpicker`            | ^1.1.0     | MIT                      | https://github.com/mchome/flutter_colorpicker                       |
| 13 | `flutter_markdown`               | ^0.7.4     | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 14 | `crypto`                         | ^3.0.3     | BSD-3-Clause             | https://github.com/dart-lang/crypto                                 |
| 15 | `syncfusion_flutter_pdf`         | 33.2.13    | **Propriétaire — voir la section dédiée ci-dessus** | https://pub.dev/packages/syncfusion_flutter_pdf   |
| 16 | `syncfusion_flutter_pdfviewer`   | 33.2.13    | **Propriétaire — voir la section dédiée ci-dessus** | https://pub.dev/packages/syncfusion_flutter_pdfviewer |
| 17 | `http`                           | ^1.2.0     | BSD-3-Clause             | https://github.com/dart-lang/http                                   |
| 18 | `intl`                           | ^0.20.0    | BSD-3-Clause             | https://github.com/dart-lang/i18n                                   |
| 19 | `url_launcher`                   | ^6.3.1     | BSD-3-Clause             | https://github.com/flutter/packages                                 |
| 20 | `google_mlkit_text_recognition`  | ^0.15.1    | MIT                      | https://github.com/flutter-ml/google_ml_kit_flutter                 |
| 21 | `google_mlkit_document_scanner`  | ^0.4.1     | MIT                      | https://github.com/flutter-ml/google_ml_kit_flutter                 |
| 22 | `html`                           | ^0.15.4    | BSD-3-Clause             | https://github.com/dart-lang/html                                   |
| 23 | `image`                          | ^4.1.3     | Apache-2.0 / MIT         | https://github.com/brendan-duncan/image                             |
| 24 | `image_picker`                   | ^1.1.2     | Apache-2.0               | https://github.com/flutter/packages                                 |
| 25 | `pointycastle`                   | ^4.0.0     | MIT (with portions BSD)  | https://github.com/bcgit/pc-dart                                    |
| 26 | `path`                           | ^1.9.0     | BSD-3-Clause             | https://github.com/dart-lang/path                                   |
| 27 | `package_info_plus`              | ^9.0.0     | BSD-3-Clause             | https://github.com/fluttercommunity/plus_plugins                    |

## Dev dependencies

| # | Package                  | Version  | License        |
| - | ------------------------ | -------- | -------------- |
| 1 | `flutter_lints`          | ^6.0.0   | BSD-3-Clause   |
| 2 | `flutter_launcher_icons` | ^0.14.0  | MIT            |

## Notices

A copy of the Apache License 2.0 is provided in the [`LICENSE`](./LICENSE) file. The [`NOTICE`](./NOTICE) file contains attribution notices for this project.

If a third-party package requires a specific notice in your distribution, please refer to that package's repository and bundled `LICENSE` / `NOTICE` files.
