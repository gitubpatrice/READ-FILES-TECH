import 'package:flutter/foundation.dart';

/// Helper pour le pattern récurrent `_busy = true → await job → _busy = false`.
///
/// Usage :
/// ```dart
/// await runBusy(_busy, (b) => setState(() => _busy = b), () async {
///   await heavyWork();
/// });
/// ```
///
/// Garantit le toggle même en cas d'exception.
Future<T?> runBusy<T>(
  bool currentBusy,
  void Function(bool) setBusy,
  Future<T> Function() job,
) async {
  if (currentBusy) return null;
  setBusy(true);
  try {
    return await job();
  } finally {
    setBusy(false);
  }
}

/// Variante avec `ValueNotifier<bool>` pour cas découplés du `setState`.
Future<T?> runBusyNotifier<T>(
  ValueNotifier<bool> busy,
  Future<T> Function() job,
) async {
  if (busy.value) return null;
  busy.value = true;
  try {
    return await job();
  } finally {
    busy.value = false;
  }
}
