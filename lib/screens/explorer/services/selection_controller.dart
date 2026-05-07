import 'package:flutter/foundation.dart';

/// Multi-select state pour l'explorateur. Indépendant du widget Flutter au-delà
/// de [ChangeNotifier] — testable sans pumpWidget.
class SelectionController extends ChangeNotifier {
  final Set<String> _paths = <String>{};

  Set<String> get paths => Set.unmodifiable(_paths);
  int get count => _paths.length;
  bool get hasSelection => _paths.isNotEmpty;

  bool isSelected(String path) => _paths.contains(path);

  void toggle(String path) {
    if (_paths.contains(path)) {
      _paths.remove(path);
    } else {
      _paths.add(path);
    }
    notifyListeners();
  }

  void select(String path) {
    if (_paths.add(path)) notifyListeners();
  }

  void deselect(String path) {
    if (_paths.remove(path)) notifyListeners();
  }

  void clear() {
    if (_paths.isEmpty) return;
    _paths.clear();
    notifyListeners();
  }

  void selectAll(Iterable<String> all) {
    _paths
      ..clear()
      ..addAll(all);
    notifyListeners();
  }

  List<String> snapshot() => _paths.toList();
}
