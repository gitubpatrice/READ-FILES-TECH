import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/update_service.dart';

class AboutScreen extends StatefulWidget {
  const AboutScreen({super.key});

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  static const _version = '1.5.0';
  static const _author  = 'Patrice Haltaya';
  static const _github  = 'github.com/gitubpatrice/READ-FILES-TECH';

  bool _checkingUpdate = false;

  static const _features = [
    (icon: Icons.visibility_outlined, label: 'Lecteur',
        desc: 'TXT, MD, JSON, XML, HTML, CSS, JS, PHP, CSV, XLSX, DOCX, ODT, PDF, ZIP, images'),
    (icon: Icons.edit_outlined, label: 'Éditeur',
        desc: 'Code (avec historique auto), CSV (tableau interactif)'),
    (icon: Icons.folder_outlined, label: 'Explorateur',
        desc: 'Navigation, recherche, tri, renommer, copier, déplacer, supprimer'),
    (icon: Icons.build_outlined, label: 'Outils',
        desc: 'Color picker, diff, hash, encodage, formater, recherche contenu, créer ZIP'),
    (icon: Icons.home_outlined, label: 'Accueil',
        desc: 'Stockage, reprendre, accès rapide dossiers, favoris, récents'),
  ];

  Future<void> _checkUpdate() async {
    setState(() => _checkingUpdate = true);
    final info = await UpdateService().checkForUpdate();
    if (!mounted) return;
    setState(() => _checkingUpdate = false);
    if (info == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vous avez déjà la dernière version ✓')),
      );
      return;
    }
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: Text('v${info.version} disponible'),
        content: Text(info.body.isNotEmpty ? info.body : 'Une nouvelle version est disponible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Plus tard')),
          FilledButton(onPressed: () => Navigator.pop(context), child: const Text('OK')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('À propos')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 40),
        children: [

          // ── Header ──────────────────────────────────────────────────────────
          Center(child: Column(children: [
            Container(
              width: 80, height: 80,
              decoration: BoxDecoration(
                color: cs.primaryContainer,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Icon(Icons.folder_open, size: 44, color: cs.primary),
            ),
            const SizedBox(height: 14),
            Text('Read Files Tech',
                style: Theme.of(context).textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
              decoration: BoxDecoration(
                color: cs.primaryContainer,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text('v$_version',
                  style: TextStyle(color: cs.primary,
                      fontWeight: FontWeight.w600, fontSize: 13)),
            ),
            const SizedBox(height: 8),
            Text('Lecteur, éditeur et explorateur de fichiers',
                style: Theme.of(context).textTheme.bodySmall,
                textAlign: TextAlign.center),
            const SizedBox(height: 14),
            FilledButton.icon(
              onPressed: _checkingUpdate ? null : _checkUpdate,
              icon: _checkingUpdate
                  ? const SizedBox(width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.system_update_outlined, size: 18),
              label: Text(_checkingUpdate ? 'Vérification…' : 'Vérifier les mises à jour'),
            ),
          ])),

          const SizedBox(height: 28),

          // ── Fonctionnalités ─────────────────────────────────────────────────
          _sectionTitle(context, 'Fonctionnalités'),
          const SizedBox(height: 8),
          ..._features.map((f) => _FeatureRow(icon: f.icon, label: f.label, desc: f.desc)),

          const SizedBox(height: 24),

          // ── Auteur ──────────────────────────────────────────────────────────
          _sectionTitle(context, 'Auteur'),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Icon(Icons.person_outline, color: cs.primary),
              ),
              title: const Text(_author),
              subtitle: const Text('Développeur'),
            ),
          ),

          const SizedBox(height: 16),

          // ── Source ──────────────────────────────────────────────────────────
          _sectionTitle(context, 'Code source'),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: const Icon(Icons.code),
              title: const Text(_github),
              subtitle: const Text('Appuyer pour copier le lien'),
              onTap: () {
                Clipboard.setData(const ClipboardData(text: 'https://$_github'));
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Lien copié dans le presse-papier')),
                );
              },
            ),
          ),

          const SizedBox(height: 16),

          // ── Aide ────────────────────────────────────────────────────────────
          _sectionTitle(context, 'Aide rapide'),
          const SizedBox(height: 8),
          _HelpCard(
            title: 'Ouvrir un fichier',
            steps: [
              'Bouton "Ouvrir un fichier" en bas de l\'accueil',
              'Ou navigue dans l\'Explorateur → tape sur le fichier',
              'Ou appuie sur un raccourci dossier pour accéder directement',
            ],
          ),
          _HelpCard(
            title: 'Permissions stockage (si dossiers vides)',
            steps: [
              'Au premier lancement, accepte "Accès à tous les fichiers"',
              'Sinon : Paramètres → Apps → Read Files Tech → Autorisations → Fichiers et médias → Autoriser tout',
            ],
          ),
          _HelpCard(
            title: 'Mise à jour',
            steps: [
              'L\'app vérifie automatiquement les mises à jour au lancement',
              'Ou appuie sur "Vérifier les mises à jour" ci-dessus',
            ],
          ),
        ],
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 2),
      child: Text(title,
          style: Theme.of(context).textTheme.titleSmall?.copyWith(
              color: Colors.grey.shade600,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.5)),
    );
  }
}

class _FeatureRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String desc;
  const _FeatureRow({required this.icon, required this.label, required this.desc});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: ListTile(
        dense: true,
        leading: Icon(icon, size: 20, color: Theme.of(context).colorScheme.primary),
        title: Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
        subtitle: Text(desc, style: const TextStyle(fontSize: 11)),
      ),
    );
  }
}

class _HelpCard extends StatelessWidget {
  final String title;
  final List<String> steps;
  const _HelpCard({required this.title, required this.steps});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
          const SizedBox(height: 6),
          ...steps.asMap().entries.map((e) => Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${e.key + 1}. ',
                  style: TextStyle(fontSize: 12,
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.w600)),
              Expanded(child: Text(e.value, style: const TextStyle(fontSize: 12))),
            ]),
          )),
        ]),
      ),
    );
  }
}
