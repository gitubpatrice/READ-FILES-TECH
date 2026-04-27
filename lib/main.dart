import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/home_screen.dart';

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
      _requestStoragePermissions();
    });
  }
}

Future<void> _requestStoragePermissions() async {
  // Ne demander qu'une seule fois après l'install. Le flag est écrit AVANT
  // les requêtes — si l'utilisateur kill l'app pendant un dialog ou refuse,
  // on ne redemande PAS au lancement suivant. L'utilisateur peut toujours
  // autoriser via Réglages (bouton dans l'explorateur si dossier inaccessible).
  final prefs = await SharedPreferences.getInstance();
  if (prefs.getBool('permissions_asked') == true) return;
  await prefs.setBool('permissions_asked', true);

  // Si déjà toutes accordées (cas où l'utilisateur les a données via Réglages
  // avant le premier "vrai" lancement), ne rien demander.
  if (await Permission.manageExternalStorage.isGranted) return;

  // Android 13+ : médias granulaires (un seul dialog regroupé via Future.wait).
  final futures = <Future<PermissionStatus>>[];
  if (await Permission.photos.isDenied) {
    futures.add(Permission.photos.request());
  }
  if (await Permission.videos.isDenied) {
    futures.add(Permission.videos.request());
  }
  if (await Permission.storage.isDenied) {
    futures.add(Permission.storage.request());
  }
  if (futures.isNotEmpty) {
    await Future.wait(futures);
  }
  // Android 11+ : MANAGE_EXTERNAL_STORAGE redirige vers une page Réglages.
  if (await Permission.manageExternalStorage.isDenied) {
    await Permission.manageExternalStorage.request();
  }
}

ThemeData _githubDarkTheme() {
  const bg       = Color(0xFF0D1117);
  const surface  = Color(0xFF161B22);
  const surface2 = Color(0xFF21262D);
  const border   = Color(0xFF30363D);
  const textPri  = Color(0xFFE6EDF3);
  const textSec  = Color(0xFF8B949E);
  const blue     = Color(0xFF58A6FF);
  const blueCont = Color(0xFF1F6FEB);

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    scaffoldBackgroundColor: bg,
    colorScheme: const ColorScheme.dark(
      primary: blue, onPrimary: Colors.white,
      primaryContainer: blueCont, onPrimaryContainer: Colors.white,
      secondary: blue, onSecondary: Colors.white,
      surface: surface, onSurface: textPri,
      surfaceContainerHighest: surface2,
      outline: border, error: Color(0xFFF85149),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: surface, foregroundColor: textPri,
      surfaceTintColor: Colors.transparent, elevation: 0,
    ),
    cardTheme: CardThemeData(
      color: surface, elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: const BorderSide(color: border),
      ),
    ),
    navigationBarTheme: const NavigationBarThemeData(
      backgroundColor: surface, indicatorColor: blueCont,
    ),
    dividerTheme: const DividerThemeData(color: border, space: 1),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true, fillColor: surface2,
      border: OutlineInputBorder(borderSide: BorderSide(color: border)),
      enabledBorder: OutlineInputBorder(borderSide: BorderSide(color: border)),
    ),
    textTheme: const TextTheme(
      bodyMedium:  TextStyle(color: textPri),
      bodySmall:   TextStyle(color: textSec),
      titleMedium: TextStyle(color: textPri, fontWeight: FontWeight.w600),
      titleSmall:  TextStyle(color: textSec),
      titleLarge:  TextStyle(color: textPri, fontWeight: FontWeight.w600),
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
  ThemeMode _themeMode = ThemeMode.system;
  bool _lastKnownPermGranted = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadTheme();
    _captureInitialPerm();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _captureInitialPerm() async {
    if (!Platform.isAndroid) return;
    _lastKnownPermGranted = await Permission.manageExternalStorage.isGranted;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) async {
    if (state != AppLifecycleState.resumed || !Platform.isAndroid) return;
    final granted = await Permission.manageExternalStorage.isGranted;
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
      setState(() => _themeMode = ThemeMode.values.firstWhere(
            (m) => m.name == saved,
            orElse: () => ThemeMode.system,
          ));
    }
  }

  Future<void> _setTheme(ThemeMode mode) async {
    setState(() => _themeMode = mode);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('theme_mode', mode.name);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Read Files Tech',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1565C0)),
        useMaterial3: true,
      ),
      darkTheme: _githubDarkTheme(),
      themeMode: _themeMode,
      home: HomeScreen(
        themeMode: _themeMode,
        onThemeChanged: _setTheme,
      ),
    );
  }
}
