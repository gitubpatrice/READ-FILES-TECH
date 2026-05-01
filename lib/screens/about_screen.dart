import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import '../services/app_update.dart';
import 'settings_screen.dart';

class AboutScreen extends StatefulWidget {
  const AboutScreen({super.key});

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  static const _version = '2.7.0';
  static const _author  = 'Patrice Haltaya';

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
    (icon: Icons.shield_outlined, label: 'Coffre fort',
        desc: 'Fichiers chiffrés AES-256-GCM (PBKDF2 600k, biométrique)'),
    (icon: Icons.transform, label: 'Conversion',
        desc: 'Images→PDF, CSV→XLSX, TXT/MD→PDF, JPG/PNG'),
    (icon: Icons.document_scanner_outlined, label: 'OCR',
        desc: 'Reconnaissance de texte locale sur images (ML Kit)'),
    (icon: Icons.compress, label: 'Compression',
        desc: 'Réduction d\'images (qualité + redim.)'),
    (icon: Icons.camera_alt_outlined, label: 'Scanner',
        desc: 'Document → PDF (caméra, détection bords, perspective)'),
    (icon: Icons.cleaning_services_outlined, label: 'Effacer EXIF',
        desc: 'Supprime GPS, date, modèle d\'appareil avant partage'),
    (icon: Icons.drive_file_rename_outline, label: 'Renommage en masse',
        desc: 'Numérotation, préfixe/suffixe, regex (multi-sélection)'),
    (icon: Icons.menu_book_outlined, label: 'Mode lecture',
        desc: 'EPUB et HTML désencombrés, taille de police variable'),
    (icon: Icons.dashboard_customize_outlined, label: 'Quick Tiles',
        desc: 'Scanner, OCR, Coffre depuis le volet de notification'),
    (icon: Icons.cloud_upload_outlined, label: 'Cloud',
        desc: 'Envoi direct vers kDrive Infomaniak ou Proton Drive'),
    (icon: Icons.draw_outlined, label: 'Signature PDF',
        desc: 'Tracer une signature manuscrite et l\'apposer sur un PDF'),
    (icon: Icons.travel_explore, label: 'Recherche globale',
        desc: 'Par nom et contenu sur tout le téléphone (Isolate)'),
    (icon: Icons.content_copy_outlined, label: 'Doublons & gros fichiers',
        desc: 'SHA-256 deux passes, libère du stockage rapidement'),
  ];

  Future<void> _checkUpdate() async {
    setState(() => _checkingUpdate = true);
    final info = await appUpdateService.checkForUpdate(force: true);
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
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          title: Text('v${info.version} disponible'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(info.body.isNotEmpty
                    ? info.body
                    : 'Une nouvelle version est disponible.'),
                if (info.expectedSha256 != null) ...[
                  const SizedBox(height: 14),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: cs.outline),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(children: [
                          Icon(Icons.verified_outlined,
                              size: 14, color: cs.primary),
                          const SizedBox(width: 6),
                          const Text('SHA-256 attendu (APK arm64-v8a)',
                              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600)),
                        ]),
                        const SizedBox(height: 6),
                        SelectableText(
                          info.expectedSha256!,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 10,
                            letterSpacing: 0.3,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Vérifiez avant install : sha256sum app-arm64-v8a-release.apk',
                          style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Plus tard')),
            FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('OK')),
          ],
        );
      },
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

          // ── Confidentialité ─────────────────────────────────────────────────
          _sectionTitle(context, 'Confidentialité'),
          const SizedBox(height: 8),
          const _PrivacyCard(),

          const SizedBox(height: 24),

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

          // ── Réglages ────────────────────────────────────────────────────────
          _sectionTitle(context, 'Réglages'),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
              leading: Icon(Icons.settings_outlined, color: cs.primary),
              title: const Text('Dossier de sortie & partage automatique'),
              subtitle: const Text(
                'Choisir où sont sauvegardés scans, conversions, signatures…',
                style: TextStyle(fontSize: 11),
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.push(context, MaterialPageRoute(
                  builder: (_) => const SettingsScreen())),
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

          const SizedBox(height: 24),

          // Sections "Aide & support" + "Mentions légales" partagées via
          // files_tech_core (couvre support, site, bug report, privacy,
          // terms, licence, copyright).
          const LegalSupportSections(
            appName: 'Read Files Tech',
            version: _version,
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

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard();

  static const _items = [
    (icon: Icons.block,              color: Color(0xFFE53935), label: 'Aucune publicité'),
    (icon: Icons.analytics_outlined, color: Color(0xFFFF7043), label: 'Aucun tracker'),
    (icon: Icons.wifi_off,           color: Color(0xFF43A047), label: 'Fonctionne hors ligne'),
    (icon: Icons.visibility_off,     color: Color(0xFF1976D2), label: 'Aucune collecte de données'),
    (icon: Icons.share_outlined,     color: Color(0xFF7B1FA2), label: 'Aucun partage de données'),
    (icon: Icons.store_mall_directory_outlined, color: Color(0xFF00897B), label: 'Sans Play Store'),
  ];

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.shield_outlined, color: Color(0xFF43A047), size: 18),
            const SizedBox(width: 6),
            Text('100 % privé — zéro surveillance',
                style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 13,
                    color: Colors.grey.shade300)),
          ]),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _items.map((item) => _Badge(
              icon: item.icon,
              label: item.label,
              color: item.color,
            )).toList(),
          ),
        ]),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  const _Badge({required this.icon, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 14, color: color),
        const SizedBox(width: 5),
        Text(label, style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w600)),
      ]),
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
