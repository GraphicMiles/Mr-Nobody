import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Agent Home (S2) — the "new tab" landing: logo, unified search, active tasks,
/// shortcuts. The unified bar routes URL → browser, search → results, task → agent.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _submit(String text) {
    final t = text.trim();
    if (t.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Routing: "$t"'), duration: const Duration(seconds: 1)),
    );
    _input.clear();
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(0, 24, 0, 24),
      children: [
        // big centered logo mark
        Padding(
          padding: const EdgeInsets.only(top: 20, bottom: 20),
          child: Text(
            'MR NOBODY',
            textAlign: TextAlign.center,
            style: AppTheme.sans(size: 26, w: FontWeight.w800),
          ),
        ),
        // unified search pill
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Container(
            height: 48,
            padding: const EdgeInsets.only(left: 16, right: 6),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: AppColors.line),
            ),
            child: Row(
              children: [
                const Icon(Icons.search, size: 18, color: AppColors.textFaint),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: _input,
                    style: AppTheme.sans(size: 14),
                    decoration: InputDecoration(
                      hintText: 'Ask Mr Nobody or enter URL…',
                      hintStyle: AppTheme.sans(size: 14, color: AppColors.textFaint),
                      border: InputBorder.none,
                      isDense: true,
                    ),
                    textInputAction: TextInputAction.go,
                    onSubmitted: _submit,
                  ),
                ),
                GestureDetector(
                  onTap: () => _submit(_input.text),
                  child: Container(
                    width: 36,
                    height: 36,
                    decoration: const BoxDecoration(
                      color: AppColors.accent,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.arrow_forward, size: 18, color: AppColors.accentInk),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SectionLabel('Active tasks'),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: AppCard(
            child: Column(
              children: [
                _TaskLine(icon: Icons.laptop_mac, title: 'Find laptop under ₦500,000', sub: 'on-device · searching'),
                Divider(),
                _TaskLine(icon: Icons.download, title: 'Download report.pdf', sub: '72%'),
                Divider(),
                _TaskLine(icon: Icons.balance, title: 'Compare phones', sub: ''),
              ],
            ),
          ),
        ),
        const SectionLabel('Shortcuts'),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: AppCard(
            child: Column(
              children: [
                _Shortcut(Icons.layers_rounded, 'Tabs'),
                Divider(),
                _Shortcut(Icons.checklist_rounded, 'Tasks'),
                Divider(),
                _Shortcut(Icons.download, 'Downloads'),
                Divider(),
                _Shortcut(Icons.settings_rounded, 'Settings'),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _TaskLine extends StatelessWidget {
  final IconData icon;
  final String title;
  final String sub;
  const _TaskLine({required this.icon, required this.title, required this.sub});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: AppColors.surface2,
              borderRadius: BorderRadius.circular(9),
            ),
            child: Icon(icon, size: 15, color: AppColors.textDim),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: AppTheme.sans(size: 13, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                if (sub.isNotEmpty) Text(sub, style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
            decoration: BoxDecoration(
              color: AppColors.accent,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text('RUNNING', style: AppTheme.mono(size: 9, color: AppColors.accentInk, w: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}

class _Shortcut extends StatelessWidget {
  final IconData icon;
  final String label;
  const _Shortcut(this.icon, this.label);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          Icon(icon, size: 18, color: AppColors.textDim),
          const SizedBox(width: 12),
          Text(label, style: AppTheme.sans(size: 13)),
        ],
      ),
    );
  }
}
