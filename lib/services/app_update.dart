import 'package:files_tech_core/files_tech_core.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// Instance partagée de [UpdateService] configurée pour Read Files Tech.
///
/// La version courante est lue dynamiquement depuis pubspec.yaml via
/// `package_info_plus` (au premier appel uniquement, puis cachée).
/// Source unique de vérité : on ne maintient plus de constante en double.
class AppUpdate {
  static UpdateService? _cached;

  static Future<UpdateService> instance() async {
    final hit = _cached;
    if (hit != null) return hit;
    final info = await PackageInfo.fromPlatform();
    final svc = UpdateService(
      owner: 'gitubpatrice',
      repo: 'read-files-tech',
      currentVersion: info.version,
    );
    _cached = svc;
    return svc;
  }

  /// Helper : `await AppUpdate.checkForUpdate(force: true)`.
  static Future<UpdateInfo?> checkForUpdate({bool force = false}) async {
    final svc = await instance();
    return svc.checkForUpdate(force: force);
  }
}
