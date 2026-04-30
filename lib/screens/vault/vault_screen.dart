import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/vault_service.dart';
import '../../widgets/rft_picker_screen.dart';

class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key});

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> with WidgetsBindingObserver {
  final _service = VaultService();
  bool _checking = true;
  bool _unlocked = false;
  bool _setup = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Au boot du coffre, purger d'éventuels fichiers déchiffrés laissés.
    _service.purgeTempDecrypted();
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Sécurité : verrouille le coffre dès que l'app passe en arrière-plan.
  /// Empêche que `_cachedKey` reste en mémoire si l'utilisateur a ouvert le
  /// coffre puis a juste appuyé sur Home (sans verrouiller manuellement).
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      if (_service.isUnlocked) {
        _service.lock();
        if (mounted) setState(() => _unlocked = false);
      }
    }
  }

  Future<void> _bootstrap() async {
    final setup = await _service.isSetup();
    if (!mounted) return;
    setState(() { _setup = setup; _checking = false; _unlocked = _service.isUnlocked; });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!_setup) {
      return _SetupScreen(onCreated: () async {
        setState(() { _setup = true; _unlocked = true; });
      });
    }
    if (!_unlocked) {
      return _UnlockScreen(
        onUnlocked: () => setState(() => _unlocked = true),
        onReset: () async {
          final ok = await _confirmReset(context);
          if (ok) {
            await _service.reset();
            if (mounted) setState(() { _setup = false; _unlocked = false; });
          }
        },
      );
    }
    return _VaultContent(service: _service, onLock: () {
      _service.lock();
      setState(() => _unlocked = false);
    });
  }

  Future<bool> _confirmReset(BuildContext context) async {
    final res = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Réinitialiser le coffre ?'),
        content: const Text('Tous les fichiers chiffrés seront supprimés. '
            'Cette action est irréversible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Réinitialiser'),
          ),
        ],
      ),
    );
    return res ?? false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup
// ─────────────────────────────────────────────────────────────────────────────

class _SetupScreen extends StatefulWidget {
  final VoidCallback onCreated;
  const _SetupScreen({required this.onCreated});

  @override
  State<_SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<_SetupScreen> {
  final _pwd1 = TextEditingController();
  final _pwd2 = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _pwd1.dispose(); _pwd2.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    setState(() { _error = null; });
    final p1 = _pwd1.text;
    final p2 = _pwd2.text;
    if (p1.length < 8) {
      setState(() => _error = 'Mot de passe : 8 caractères minimum');
      return;
    }
    if (p1 != p2) {
      setState(() => _error = 'Les mots de passe ne correspondent pas');
      return;
    }
    setState(() => _busy = true);
    try {
      await VaultService().setupWithPassword(p1);
      widget.onCreated();
    } catch (e) {
      setState(() { _error = 'Erreur : $e'; _busy = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Créer un coffre fort')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Icon(Icons.shield_outlined, size: 56),
          const SizedBox(height: 12),
          const Text(
            'Le coffre chiffre vos fichiers avec AES-256-GCM. '
            'Le mot de passe est dérivé localement (PBKDF2 600 000 itérations) '
            'et n\'est jamais stocké.',
            style: TextStyle(fontSize: 13),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _pwd1,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: 'Mot de passe',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.lock_outline),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _pwd2,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: 'Confirmer',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.lock_outline),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: _busy ? null : _create,
            icon: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.check),
            label: Text(_busy ? 'Création…' : 'Créer le coffre'),
          ),
          const SizedBox(height: 24),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.amber.withValues(alpha: 0.10),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
            ),
            child: const Row(children: [
              Icon(Icons.warning_amber, color: Colors.amber, size: 18),
              SizedBox(width: 8),
              Expanded(child: Text(
                'Aucune récupération possible : si vous oubliez le mot de passe, '
                'les fichiers chiffrés seront irrécupérables.',
                style: TextStyle(fontSize: 11),
              )),
            ]),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unlock
// ─────────────────────────────────────────────────────────────────────────────

class _UnlockScreen extends StatefulWidget {
  final VoidCallback onUnlocked;
  final VoidCallback onReset;
  const _UnlockScreen({required this.onUnlocked, required this.onReset});

  @override
  State<_UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<_UnlockScreen> {
  final _pwd = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() { _pwd.dispose(); super.dispose(); }

  Future<void> _unlock() async {
    setState(() { _busy = true; _error = null; });
    final ok = await VaultService().unlockWithPassword(_pwd.text);
    if (!mounted) return;
    if (ok) {
      widget.onUnlocked();
    } else {
      setState(() { _error = 'Mot de passe incorrect'; _busy = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Coffre fort')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const SizedBox(height: 20),
          const Icon(Icons.lock_outline, size: 56),
          const SizedBox(height: 16),
          const Text('Coffre verrouillé',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 24),
          TextField(
            controller: _pwd,
            obscureText: true,
            autofocus: true,
            onSubmitted: (_) => _unlock(),
            decoration: const InputDecoration(
              labelText: 'Mot de passe',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.lock_outline),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _unlock,
            icon: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.lock_open),
            label: const Text('Déverrouiller'),
          ),
          const SizedBox(height: 32),
          TextButton.icon(
            icon: const Icon(Icons.delete_forever_outlined, color: Colors.red),
            label: const Text('Réinitialiser le coffre',
                style: TextStyle(color: Colors.red)),
            onPressed: widget.onReset,
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content (déverrouillé)
// ─────────────────────────────────────────────────────────────────────────────

class _VaultContent extends StatefulWidget {
  final VaultService service;
  final VoidCallback onLock;
  const _VaultContent({required this.service, required this.onLock});

  @override
  State<_VaultContent> createState() => _VaultContentState();
}

class _VaultContentState extends State<_VaultContent> {
  List<File> _files = [];
  bool _loading = true;

  @override
  void initState() { super.initState(); _refresh(); }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final files = await widget.service.listFiles();
    if (!mounted) return;
    setState(() { _files = files; _loading = false; });
  }

  Future<void> _import() async {
    final messenger = ScaffoldMessenger.of(context);
    final paths = await RftPickerScreen.pickMany(context,
        title: 'Importer dans le coffre');
    if (paths == null || paths.isEmpty) return;
    int ok = 0, skip = 0, fail = 0;
    for (final p in paths) {
      try {
        await widget.service.importFileSafe(File(p));
        ok++;
      } on FileSystemException {
        if (!mounted) return;
        final overwrite = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Fichier déjà présent'),
            content: Text('"${p.split(RegExp(r'[/\\\\]')).last}" existe déjà dans le coffre. Écraser ?'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Garder')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Écraser')),
            ],
          ),
        );
        if (overwrite == true) {
          try {
            await widget.service.importFileSafe(File(p), overwrite: true);
            ok++;
          } catch (_) { fail++; }
        } else { skip++; }
      } catch (_) { fail++; }
    }
    await _refresh();
    if (!mounted) return;
    final parts = <String>[
      if (ok > 0)   '$ok chiffré${ok > 1 ? 's' : ''}',
      if (skip > 0) '$skip ignoré${skip > 1 ? 's' : ''}',
      if (fail > 0) '$fail erreur${fail > 1 ? 's' : ''}',
    ];
    messenger.showSnackBar(SnackBar(content: Text(parts.join(' · '))));
  }

  Future<void> _share(File enc) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final tmp = await widget.service.decryptToTemp(enc);
      await Share.shareXFiles([XFile(tmp.path)]);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _export(File enc) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.platform.getDirectoryPath();
    if (destDir == null) return;
    try {
      final out = await widget.service.exportFile(enc, destDir);
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Exporté : ${out.path.split('/').last}')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _delete(File enc) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer du coffre'),
        content: Text('Supprimer "${_displayName(enc)}" ? Action irréversible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Supprimer')),
        ],
      ),
    );
    if (confirm != true) return;
    await widget.service.deleteFile(enc);
    _refresh();
  }

  String _displayName(File f) {
    final n = f.path.split(RegExp(r'[/\\]')).last;
    return n.endsWith('.enc') ? n.substring(0, n.length - 4) : n;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Coffre fort'),
        actions: [
          IconButton(
            icon: const Icon(Icons.lock_outline),
            tooltip: 'Verrouiller',
            onPressed: widget.onLock,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _files.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.shield_outlined, size: 64, color: Colors.grey.shade400),
                      const SizedBox(height: 12),
                      const Text('Coffre vide'),
                      const SizedBox(height: 4),
                      Text('Importez un fichier pour le chiffrer',
                          style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
                    ],
                  ),
                )
              : ListView.builder(
                  itemCount: _files.length,
                  itemBuilder: (_, i) {
                    final f = _files[i];
                    final size = f.lengthSync();
                    return ListTile(
                      leading: const Icon(Icons.lock, color: Colors.green),
                      title: Text(_displayName(f), overflow: TextOverflow.ellipsis),
                      subtitle: Text(_fmt(size)),
                      trailing: PopupMenuButton<String>(
                        onSelected: (v) {
                          if (v == 'share')  _share(f);
                          if (v == 'export') _export(f);
                          if (v == 'delete') _delete(f);
                        },
                        itemBuilder: (_) => const [
                          PopupMenuItem(value: 'share', child: ListTile(
                              leading: Icon(Icons.share), title: Text('Partager (déchiffré)'))),
                          PopupMenuItem(value: 'export', child: ListTile(
                              leading: Icon(Icons.download_outlined), title: Text('Exporter…'))),
                          PopupMenuItem(value: 'delete', child: ListTile(
                              leading: Icon(Icons.delete_outline, color: Colors.red),
                              title: Text('Supprimer'))),
                        ],
                      ),
                    );
                  },
                ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _import,
        icon: const Icon(Icons.add),
        label: const Text('Importer'),
      ),
    );
  }

  String _fmt(int b) {
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${(b / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
