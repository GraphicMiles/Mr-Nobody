import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../bridge/native_bridge.dart';
import 'privacy_screen.dart';
import 'downloads_screen.dart';
import 'clear_data_screen.dart';

/// Settings (S6) — toggles persisted to the core, value rows with anchored
/// bottom-sheet menus, and navigation to the Clear-data/Downloads/Privacy screens.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool history = false;
  bool js = true;
  bool suggest = false;
  String profile = 'Balanced';
  String provider = 'Local';
  String engine = 'DuckDuckGo';

  @override
  void initState() {
    super.initState();
    NativeBridge.isHistoryEnabled().then((v) { if (mounted) setState(() => history = v); }).catchError((_) {});
  }

  void _pick(String title, List<String> options, String current, ValueChanged<String> onPick) {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(title.toUpperCase(), style: AppTheme.mono(size: 10, color: AppColors.textFaint, w: FontWeight.w600)),
            ),
            for (final o in options)
              ListTile(
                title: Text(o, style: AppTheme.sans(size: 14, color: o == current ? AppColors.accent : AppColors.text, w: o == current ? FontWeight.w700 : FontWeight.w400)),
                onTap: () { onPick(o); Navigator.pop(c); },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          color: AppColors.surface,
          padding: EdgeInsets.only(left: 8, top: 8 + MediaQuery.of(context).padding.top, right: 12, bottom: 8),
          child: Row(
            children: [
              IconButton(onPressed: () {}, icon: const Icon(Icons.chevron_left, color: AppColors.textDim, size: 26)),
              Text('Settings', style: AppTheme.sans(size: 16, w: FontWeight.w700)),
            ],
          ),
        ),
        Expanded(
          child: ListView(
            children: [
              const SectionLabel('Browsing'),
              _card([
                _toggle('Save browsing history', history, (v) { setState(() => history = v); NativeBridge.setHistoryEnabled(v); }),
                const Divider(),
                _toggle('JavaScript', js, (v) => setState(() => js = v)),
                const Divider(),
                _toggle('Search suggestions', suggest, (v) => setState(() => suggest = v)),
              ]),
              const SectionLabel('Privacy & data'),
              _card([
                _value('Privacy profile', profile, () => _pick('Privacy profile', ['Balanced', 'Strict', 'Maximum'], profile, (v) => setState(() => profile = v))),
                const Divider(),
                _value('AI provider', provider, () => _pick('AI provider', ['Local (on-device)', 'Gemini', 'Groq', 'OpenAI-compatible'], provider, (v) => setState(() => provider = v))),
                const Divider(),
                _value('Terminal', 'off', () => _pick('Terminal', ['Off', 'On (sandboxed)'], 'Off', (_) {})),
              ]),
              const SectionLabel('Data & controls'),
              _card([
                _value('Search engine', engine, () => _pick('Search engine', ['DuckDuckGo', 'Startpage', 'Bing'], engine, (v) => setState(() => engine = v))),
                const Divider(),
                _nav('Bookmarks', () => _info('Bookmarks', 'No bookmarks yet')),
                const Divider(),
                _nav('Clear browsing data', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ClearDataScreen()))),
                const Divider(),
                _nav('Downloads', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const DownloadsScreen()))),
                const Divider(),
                _nav('Privacy dashboard', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PrivacyScreen()))),
                const Divider(),
                _nav('About', () => _info('Mr Nobody', 'A tiny native privacy browser.\nNo ads, no trackers, no history by default.')),
              ]),
            ],
          ),
        ),
      ],
    );
  }

  void _info(String title, String msg) {
    showDialog(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: Text(title, style: AppTheme.sans(size: 16, w: FontWeight.w700)),
        content: Text(msg, style: AppTheme.sans(size: 13, color: AppColors.textDim)),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('OK', style: TextStyle(color: AppColors.accent)))],
      ),
    );
  }

  Widget _card(List<Widget> children) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: AppCard(child: Column(children: children)),
    );
  }

  Widget _toggle(String label, bool value, ValueChanged<bool> onChanged) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(label, style: AppTheme.sans(size: 14))),
          Switch(
            value: value,
            onChanged: onChanged,
            activeTrackColor: AppColors.accent,
            inactiveTrackColor: AppColors.surface2,
            thumbColor: WidgetStateProperty.all(value ? AppColors.accentInk : AppColors.textFaint),
          ),
        ],
      ),
    );
  }

  Widget _value(String label, String value, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Expanded(child: Text(label, style: AppTheme.sans(size: 14))),
            Text(value, style: AppTheme.mono(size: 11.5, color: AppColors.textFaint)),
            const SizedBox(width: 8),
            const Icon(Icons.chevron_right, size: 18, color: AppColors.textFaint),
          ],
        ),
      ),
    );
  }

  Widget _nav(String label, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Expanded(child: Text(label, style: AppTheme.sans(size: 14))),
            const Icon(Icons.chevron_right, size: 18, color: AppColors.textFaint),
          ],
        ),
      ),
    );
  }
}
