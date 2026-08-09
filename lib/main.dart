import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/home_screen.dart';
import 'screens/tools/scanner_screen.dart';
import 'screens/tools/ocr_screen.dart';
import 'screens/vault/vault_screen.dart';
import 'services/storage_access.dart';
import 'services/vault_service.dart';
import 'utils/app_constants.dart';

final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  // Lance l'app immédiatement. Les permissions sont demandées en background
  // après le premier frame — sinon l'écran de splash reste figé pendant que
  // le système enchaîne plusieurs dialogs.
  runApp(const ReadFilesTechApp());
  if (Platform.isAndroid) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _requestStoragePermissions(_navigatorKey.currentContext);
    });
  }
  // F6 : purge des fichiers vault déchiffrés laissés au cycle précédent
  // (cas process-kill avant lock). Best-effort, fire-and-forget.
  unawaited(VaultService.instance.purgeTempDecrypted());
}

/// Au 1er lancement, affiche un dialog welcome explicatif puis demande
/// directement MANAGE_EXTERNAL_STORAGE (page Réglages dédiée "Tous les
/// fichiers"). Pas de pop-ups intermédiaires READ_MEDIA_* qui embrouillent
/// l'utilisateur — MANAGE_EXTERNAL_STORAGE couvre tout en pratique.
/// Flag écrit AVANT pour ne jamais redemander.
Future<void> _requestStoragePermissions(BuildContext? context) async {
  final prefs = await SharedPreferences.getInstance();
  if (prefs.getBool('permissions_asked') == true) return;
  await prefs.setBool('permissions_asked', true);

  // Court-circuit si déjà accordée. `StorageAccess` choisit la permission
  // qui existe réellement sur cette version d'Android.
  if (await StorageAccess.isGranted()) return;

  // Dialog welcome explicatif (si context dispo)
  if (context == null || !context.mounted) return;

  final wantContinue = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => AlertDialog(
      icon: const Icon(Icons.folder_open, size: 36),
      title: const Text('Bienvenue dans Read Files Tech'),
      content: const Text(
        'Pour explorer et lire vos fichiers (PDF, DOCX, images, ZIP, etc.) '
        'partout sur votre téléphone, l\'app a besoin de l\'accès "Tous les fichiers".\n\n'
        '➜ Vous serez redirigé(e) vers les Réglages.\n'
        '➜ Activez le toggle "Autoriser l\'accès à tous les fichiers".\n'
        '➜ Revenez à l\'app.\n\n'
        'Aucun fichier n\'est transmis ailleurs.',
        style: TextStyle(fontSize: 13),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Plus tard'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('Ouvrir les Réglages'),
        ),
      ],
    ),
  );
  if (wantContinue != true) return;

  // Android 11+ : page « Tous les fichiers » dédiée. Android ≤ 10 : boîte de
  // dialogue runtime classique sur READ/WRITE_EXTERNAL_STORAGE, seule chose
  // qui existe là-bas.
  await StorageAccess.request();
}

ThemeData _githubDarkTheme() {
  const bg = Color(0xFF0D1117);
  const surface = Color(0xFF161B22);
  const surface2 = Color(0xFF21262D);
  const border = Color(0xFF30363D);
  const textPri = Color(0xFFE6EDF3);
  const textSec = Color(0xFF8B949E);
  const blue = Color(0xFF58A6FF);
  const blueCont = Color(0xFF1F6FEB);

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    scaffoldBackgroundColor: bg,
    colorScheme: const ColorScheme.dark(
      primary: blue,
      onPrimary: Colors.white,
      primaryContainer: blueCont,
      onPrimaryContainer: Colors.white,
      secondary: blue,
      onSecondary: Colors.white,
      surface: surface,
      onSurface: textPri,
      surfaceContainerHighest: surface2,
      outline: border,
      error: Color(0xFFF85149),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: surface,
      foregroundColor: textPri,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
    ),
    cardTheme: CardThemeData(
      color: surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: const BorderSide(color: border),
      ),
    ),
    navigationBarTheme: const NavigationBarThemeData(
      backgroundColor: surface,
      indicatorColor: blueCont,
    ),
    dividerTheme: const DividerThemeData(color: border, space: 1),
    // v2.13.2 (U1/P1) — `floating` global pour que tous les SnackBar bruts
    // (pas seulement ceux passant par showFloatingSnack/showErrorSnack)
    // respectent le pattern Files Tech. Avant : comportement `fixed` par
    // défaut → SnackBar collait au bas et poussait le FAB. Couvre les 30+
    // sites inline sans avoir à les migrer individuellement.
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: surface2,
      border: OutlineInputBorder(borderSide: BorderSide(color: border)),
      enabledBorder: OutlineInputBorder(borderSide: BorderSide(color: border)),
    ),
    textTheme: const TextTheme(
      bodyMedium: TextStyle(color: textPri),
      bodySmall: TextStyle(color: textSec),
      titleMedium: TextStyle(color: textPri, fontWeight: FontWeight.w600),
      titleSmall: TextStyle(color: textSec),
      titleLarge: TextStyle(color: textPri, fontWeight: FontWeight.w600),
    ),
  );
}

class ReadFilesTechApp extends StatefulWidget {
  const ReadFilesTechApp({super.key});

  @override
  State<ReadFilesTechApp> createState() => _ReadFilesTechAppState();
}

class _ReadFilesTechAppState extends State<ReadFilesTechApp>
    with WidgetsBindingObserver {
  static const _lifecycleChannel = MethodChannel('com.readfilestech/lifecycle');
  static const _shortcutChannel = MethodChannel('com.readfilestech/shortcut');
  ThemeMode _themeMode = ThemeMode.system;
  bool _lastKnownPermGranted = false;
  Timer? _autoLockTimer;

  /// Délai de verrouillage automatique du coffre après mise en pause.
  /// Source unique : [AppConstants.autoLockDelay].
  static const _autoLockDelay = AppConstants.autoLockDelay;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    VaultService.unlockedNotifier.addListener(_onVaultLockStateChanged);
    _loadTheme();
    _captureInitialPerm();
    _initShortcuts();
  }

  /// Récupère le shortcut éventuel (cold-start) et écoute les invocations
  /// `onShortcut` (warm-start via onNewIntent côté Kotlin).
  Future<void> _initShortcuts() async {
    _shortcutChannel.setMethodCallHandler((call) async {
      if (call.method == 'onShortcut') {
        _handleShortcut(call.arguments as String?);
      }
      return null;
    });
    // Cold start
    try {
      final shortcut = await _shortcutChannel.invokeMethod<String>(
        'getShortcut',
      );
      if (shortcut != null) {
        WidgetsBinding.instance.addPostFrameCallback(
          (_) => _handleShortcut(shortcut),
        );
      }
    } catch (_) {}
  }

  void _handleShortcut(String? shortcut) {
    if (shortcut == null) return;
    final ctx = _navigatorKey.currentContext;
    if (ctx == null) return;
    final Widget? target = switch (shortcut) {
      'scanner' => const ScannerScreen(),
      'ocr' => const OcrScreen(),
      'vault' => const VaultScreen(),
      _ => null,
    };
    if (target == null) return;
    Navigator.of(ctx).push(MaterialPageRoute(builder: (_) => target));
  }

  @override
  void dispose() {
    _autoLockTimer?.cancel();
    _idleLockTimer?.cancel();
    VaultService.unlockedNotifier.removeListener(_onVaultLockStateChanged);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _captureInitialPerm() async {
    if (!Platform.isAndroid) return;
    _lastKnownPermGranted = await StorageAccess.isGranted();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) async {
    // Auto-lock vault GLOBAL (P0 sécu) — couvre tous les écrans, pas
    // seulement VaultScreen. Si l'app est tuée par OS LMK pendant les 30 s,
    // F6 (purge boot) nettoiera les plaintexts au prochain lancement.
    if (Platform.isAndroid) {
      switch (state) {
        case AppLifecycleState.paused:
        case AppLifecycleState.hidden:
        case AppLifecycleState.inactive:
          if (VaultService.instance.isUnlocked) {
            _autoLockTimer?.cancel();
            _autoLockTimer = Timer(_autoLockDelay, () {
              if (VaultService.instance.isUnlocked) {
                VaultService.instance.lock();
              }
            });
          }
          // V-L4 — plus de purge ici. Ouvrir une share-sheet fait passer
          // l'app en `inactive` puis `paused` : purger à cet instant
          // supprimait le fichier que l'application destinataire venait tout
          // juste de recevoir. Et `cache/share_plus/` n'appartient pas au
          // coffre — c'est le staging que share_plus utilise pour les 29
          // partages de l'app, coffre ou non.
          //
          // Le plaintext est borné par le VERROUILLAGE, qui purge : 30 s
          // après le passage en arrière-plan (le timer ci-dessus), 3 min
          // d'inactivité au premier plan, à la sortie de l'écran du coffre,
          // ou immédiatement sur `detached`. Et `main()` repurge au démarrage
          // si le process a été tué entre-temps.
          break;
        case AppLifecycleState.resumed:
          _autoLockTimer?.cancel();
          break;
        case AppLifecycleState.detached:
          // Process en train d'être tué : lock IMMÉDIAT pour éviter la
          // fenêtre où la clé reste en RAM.
          _autoLockTimer?.cancel();
          if (VaultService.instance.isUnlocked) {
            VaultService.instance.lock();
          }
          break;
      }
    }
    if (state != AppLifecycleState.resumed || !Platform.isAndroid) return;
    final granted = await StorageAccess.isGranted();
    // Transition denied → granted : Samsung n'applique pas la nouvelle perm
    // au process en cours. recreate() force Android à reloader.
    if (granted && !_lastKnownPermGranted) {
      _lastKnownPermGranted = true;
      try {
        await _lifecycleChannel.invokeMethod('recreateActivity');
      } catch (_) {}
    } else {
      _lastKnownPermGranted = granted;
    }
  }

  Future<void> _loadTheme() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString('theme_mode');
    if (saved != null && mounted) {
      setState(
        () => _themeMode = ThemeMode.values.firstWhere(
          (m) => m.name == saved,
          orElse: () => ThemeMode.system,
        ),
      );
    }
  }

  Future<void> _setTheme(ThemeMode mode) async {
    setState(() => _themeMode = mode);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('theme_mode', mode.name);
  }

  /// V-H1 v2.15 — verrouillage sur inactivité **au premier plan**.
  ///
  /// L'auto-lock existant ne couvrait que la mise en arrière-plan. Un coffre
  /// déverrouillé puis laissé à l'écran restait ouvert aussi longtemps que
  /// l'app tournait : téléphone posé sur une table, écran éteint par le
  /// système (ce qui ne produit pas de `paused` sur tous les constructeurs),
  /// et le coffre était rouvrable sans mot de passe.
  ///
  /// Le compteur est remis à zéro à chaque interaction tactile. Il n'est armé
  /// que lorsque le coffre est effectivement déverrouillé : aucun timer ne
  /// tourne dans le cas courant.
  Timer? _idleLockTimer;

  void _touch() {
    if (!VaultService.instance.isUnlocked) {
      _idleLockTimer?.cancel();
      _idleLockTimer = null;
      return;
    }
    _idleLockTimer?.cancel();
    _idleLockTimer = Timer(AppConstants.foregroundIdleLockDelay, () {
      if (VaultService.instance.isUnlocked) VaultService.instance.lock();
    });
  }

  /// Arme le minuteur au déverrouillage et le désarme au verrouillage.
  /// Sans ce branchement, un utilisateur qui déverrouille puis ne touche plus
  /// l'écran ne déclencherait jamais `_touch()`.
  void _onVaultLockStateChanged() => _touch();

  @override
  Widget build(BuildContext context) {
    return Listener(
      // `behavior: deferToChild` laisserait passer les zones sans hit-test.
      // On veut voir *toute* interaction, y compris sur un fond vide.
      behavior: HitTestBehavior.translucent,
      onPointerDown: (_) => _touch(),
      child: _buildApp(),
    );
  }

  Widget _buildApp() {
    return MaterialApp(
      title: 'Read Files Tech',
      navigatorKey: _navigatorKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1565C0)),
        useMaterial3: true,
        // v2.13.2 (U1/P1) — pattern Files Tech : floating sur tous les SnackBar.
        snackBarTheme: const SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
        ),
      ),
      darkTheme: _githubDarkTheme(),
      themeMode: _themeMode,
      home: HomeScreen(themeMode: _themeMode, onThemeChanged: _setTheme),
    );
  }
}
