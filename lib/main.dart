import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  runApp(const ReadFilesTechApp());
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

class _ReadFilesTechAppState extends State<ReadFilesTechApp> {
  ThemeMode _themeMode = ThemeMode.system;

  @override
  void initState() {
    super.initState();
    _loadTheme();
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
