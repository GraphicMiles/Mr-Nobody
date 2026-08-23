import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// The About page: what the app is, who made it, its license, and a GitHub
/// link. Replaces the old one-line dialog with a full, scrollable, nicely
/// formatted screen.
///
/// It is also honest about scope: the "not yet" features (remote worker,
/// credits, other design platforms) are listed as coming-soon rather than
/// implied to be available.
class AboutScreen extends StatelessWidget {
  /// Opens an http(s) URL in the app's browser when supplied.
  final void Function(String url)? onOpenUrl;

  const AboutScreen({super.key, this.onOpenUrl});

  static const String githubUrl = 'https://github.com/GraphicMiles/Mr-Nobody';
  static const String _repo = 'github.com/GraphicMiles/Mr-Nobody';

  /// The public landing page and release notes, served by the update service.
  /// The same server also answers the app's update check — the page is the
  /// human face of the same release data the app reads.
  static const String websiteUrl = 'https://mrnobody-updates.onrender.com';
  static const String _site = 'mrnobody-updates.onrender.com';

  void _open(String url) {
    final opener = onOpenUrl;
    if (opener != null) {
      opener(url);
    }
    // else: no browser callback in this context (e.g. a widget test). Do
    // nothing rather than pretend we navigated.
  }

  void _openGithub() => _open(githubUrl);

  void _openWebsite() => _open(websiteUrl);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'About',
        onBack: () => Navigator.of(context).pop(),
        children: [
        // ------------------------------------------------------------ brand
        const _Brand(icon: Icons.visibility_outlined, name: 'Mr Nobody'),
        const SizedBox(height: 14),

        // ---------------------------------------------------------- summary
        AppCard(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'A small, private, agentic browser.',
                  style: AppTheme.sans(
                      size: 14, color: AppColors.textDim, w: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Text(
                  'Mr Nobody is a privacy-focused Android browser with a built-in '
                  'task agent. It can search the web, read sources and answer with '
                  'citations, download files, and run recurring checks — while '
                  'blocking ads and trackers locally, stripping tracking parameters, '
                  'and routing traffic through Tor when you ask it to. '
                  'The browser and the agent share the same privacy controls, '
                  'download system and network routing.',
                  style: AppTheme.sans(
                      size: 12, color: AppColors.textMuted, height: 1.5),
                ),
                const SizedBox(height: 10),
                const _Divider(),
                const SizedBox(height: 10),
                Text(
                  'The agent is local-first and honest. Light, well-scoped tasks are '
                  'handled on the device with deterministic code (no AI provider '
                  'request). You can optionally add Gemini, Groq or an '
                  'OpenAI-compatible provider for planning and answer synthesis; '
                  'tool execution still passes through Mr Nobody’s local policy and '
                  'execution ledger.',
                  style: AppTheme.sans(
                      size: 12, color: AppColors.textMuted, height: 1.5),
                ),
              ],
            ),
          ),
        ),

        const SizedBox(height: 16),

        // --------------------------------------------------------- features
        const SectionLabel('What it does'),
        AppCard(
          child: Column(
            children: withDividers([
              const _InfoRow(
                  icon: Icons.shield_outlined,
                  label: 'Private browsing',
                  detail: 'Local ad/tracker blocking, tracking-parameter stripping, '
                      'history off by default, third-party cookies and mixed content '
                      'disabled, and normal / private / Nobody modes.'),
              const _InfoRow(
                  icon: Icons.cloud_done_outlined,
                  label: 'Agent tasks',
                  detail: 'Search the web, read sources, produce cited answers, '
                      'download files, follow up in the same task, and run recurring '
                      'checks. The local path is deterministic and offline-capable.'),
              const _InfoRow(
                  icon: Icons.download_done_outlined,
                  label: 'App-owned downloads',
                  detail: 'Download to a folder you choose, with pause, resume, '
                      'retry, cancellation, validated range resumption and recovery '
                      'after process death — never handed to the system downloader.'),
              const _InfoRow(
                  icon: Icons.privacy_tip_outlined,
                  label: 'No tracking by Mr Nobody',
                  detail: 'No analytics, no advertising SDK, no silent startup '
                      'request, and no auto-generated browsing history.'),
            ]),
          ),
        ),

        const SizedBox(height: 16),

        // ---------------------------------------------------- not yet (soon)
        const SectionLabel('Coming soon'),
        AppCard(
          child: Column(
            children: withDividers([
              const ComingSoonRow(
                label: 'Remote worker',
                detail: 'Runs whole tasks on a server for long, heavy or background '
                    'work. The app side is ready; the production server is being built.',
                icon: Icons.cloud_outlined,
              ),
              const ComingSoonRow(
                label: 'Credits & payments',
                detail: 'Purchases, refunds and a billing ledger arrive once remote '
                    'execution is reliable.',
                icon: Icons.account_balance_wallet_outlined,
              ),
              const ComingSoonRow(
                label: 'Figma & Adobe Express',
                detail: 'Additional design-platform integrations are planned after '
                    'Canva.',
                icon: Icons.design_services_outlined,
              ),
            ]),
          ),
        ),

        const SizedBox(height: 16),

        // ------------------------------------------------------------- misc
        const SectionLabel('Details'),
        AppCard(
          child: Column(
            children: withDividers([
              const _InfoRow(
                icon: Icons.code,
                label: 'Open source',
                detail: 'Released under the MIT License. You can read, modify and '
                    'redistribute the source.',
              ),
              const _InfoRow(
                icon: Icons.rocket_launch_outlined,
                label: 'Version',
                detail: 'Development build — see the app’s commit history for exact '
                    'features and fixes.',
              ),
            ]),
          ),
        ),

        // ------------------------------------------------------------- links
        const SectionLabel('Get involved'),
        AppCard(
          child: Column(
            children: withDividers([
              _InfoRow(
                icon: Icons.public_outlined,
                label: 'Website',
                detail: _site,
                onTap: _openWebsite,
              ),
              _InfoRow(
                icon: Icons.link,
                label: 'Source code',
                detail: _repo,
                onTap: _openGithub,
              ),
              const _InfoRow(
                  icon: Icons.people_outline,
                  label: 'Contributing',
                  detail: 'Docs and roadmap live in the repository. Fixes with tests '
                      'are welcome.'),
            ]),
          ),
        ),

        const SizedBox(height: 16),

        // -------------------------------------------------------- attribution
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
          child: Text(
            'Third-party components keep their own licenses and notices: Android '
            'System WebView is supplied by the device; bundled Tor components are '
            'used under their BSD-style licenses; bundled font licenses remain in '
            'app/assets/fonts/. "Tor" and the onion logo are trademarks of The Tor '
            'Project, Inc. — Mr Nobody is an independent product, not affiliated '
            'with or endorsed by The Tor Project.',
            style: AppTheme.sans(
                size: 10.5, color: AppColors.textMuted, height: 1.5),
          ),
        ),

        const SizedBox(height: 10),

        // -------------------------------------------------------- signature
        Center(
          child: Column(
            children: [
              Text('Graphic Miles',
                  style: AppTheme.sans(
                      size: 12.5,
                      color: AppColors.textDim,
                      w: FontWeight.w600)),
              const SizedBox(height: 2),
              Text('2026 · MIT License',
                  style: AppTheme.sans(
                      size: 11, color: AppColors.textFaint)),
              const SizedBox(height: 8),
              Text('Made freely available as open source',
                  style: AppTheme.sans(
                      size: 10, color: AppColors.textMuted)),
            ],
          ),
        ),
        ],
      ),
    );
  }
}

/// The logo-backed brand block at the top of the About page.
class _Brand extends StatelessWidget {
  final IconData icon;
  final String name;

  const _Brand({required this.icon, required this.name});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
      child: Row(
        children: [
          Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.isWarm ? null : AppColors.surface,
              gradient: AppColors.isWarm
                  ? LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [AppColors.surface2, AppColors.surface],
                    )
                  : null,
              border: Border.all(color: AppColors.lineStrong),
            ),
            child: Icon(icon, size: 22, color: AppColors.accent),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name,
                  style: AppTheme.sans(
                      size: 16, color: AppColors.textDim, w: FontWeight.w700)),
              const SizedBox(height: 2),
              Text('Private, agentic browser',
                  style: AppTheme.sans(
                      size: 11, color: AppColors.textFaint)),
            ],
          ),
        ],
      ),
    );
  }
}

/// A label + detail pair used inside About’s feature cards.
class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String detail;
  final VoidCallback? onTap;

  const _InfoRow({
    required this.icon,
    required this.label,
    required this.detail,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 16, color: AppColors.textFaint),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label,
                      style: AppTheme.sans(
                          size: 12.5,
                          color: AppColors.textDim,
                          w: FontWeight.w600)),
                  const SizedBox(height: 3),
                  Text(detail,
                      style: AppTheme.sans(
                          size: 11.5,
                          color: AppColors.textMuted,
                          height: 1.5)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A soft horizontal divider used inside the summary card.
class _Divider extends StatelessWidget {
  const _Divider();

  @override
  Widget build(BuildContext context) {
    return Container(height: 1, color: AppColors.line);
  }
}
