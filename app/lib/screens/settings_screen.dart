import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/anchored_menu.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';
import 'ai_provider_screen.dart';
import 'clear_data_screen.dart';
import 'downloads_screen.dart';
import 'privacy_screen.dart';

/// Settings (S6) — three groups, exactly as in `#v-settings`:
/// Browsing (toggles), Agent (profile / provider / terminal), Data.
///
/// Every control writes straight through to the Java core, so what the screen
/// shows is what is actually persisted.
class SettingsScreen extends StatefulWidget {
  final VoidCallback? onBack;
  final ScrollController? scrollController;

  const SettingsScreen({super.key, this.onBack, this.scrollController});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _state = AppState.instance;
  int? _downloadCount;
  String _downloadFolder = 'Downloads (system)';
  bool _customFolder = false;

  @override
  void initState() {
    super.initState();
    _state.load();
    _loadDownloadCount();
    _loadDownloadFolder();
  }

  Future<void> _loadDownloadFolder() async {
    final folder = await NativeBridge.guard(
      NativeBridge.downloadFolder,
      const <String, dynamic>{},
      'download folder unavailable',
    );
    if (!mounted || folder.isEmpty) return;
    setState(() {
      _downloadFolder = folder['label'] as String? ?? _downloadFolder;
      _customFolder = folder['custom'] as bool? ?? false;
    });
  }

  Future<void> _chooseDownloadFolder(BuildContext rowContext) async {
    if (_customFolder) {
      final picked = await showAnchoredMenu<String>(
        context: rowContext,
        title: 'Download folder',
        selected: 'keep',
        options: const [
          MenuOption(id: 'keep', label: 'Choose another folder', icon: Icons.folder_open),
          MenuOption(id: 'system', label: 'Use system Downloads', icon: Icons.undo),
        ],
      );
      if (picked == null) return;
      if (picked == 'system') {
        final result = await NativeBridge.guard(
          NativeBridge.clearDownloadFolder,
          const <String, dynamic>{},
          'could not reset the download folder',
        );
        if (!mounted) return;
        setState(() {
          _downloadFolder = result['label'] as String? ?? 'Downloads (system)';
          _customFolder = false;
        });
        AppToast.show(context, 'Saving to system Downloads');
        return;
      }
    }
    final result = await NativeBridge.guard(
      NativeBridge.pickDownloadFolder,
      const <String, dynamic>{},
      'folder picker unavailable',
    );
    if (!mounted) return;
    if (result['cancelled'] == true) return;
    final error = result['error'] as String?;
    if (error != null) {
      AppToast.show(context, error);
      return;
    }
    setState(() {
      _downloadFolder = result['label'] as String? ?? _downloadFolder;
      _customFolder = result['custom'] as bool? ?? false;
    });
    AppToast.show(context, 'Saving downloads to $_downloadFolder');
  }

  Future<void> _loadDownloadCount() async {
    final items = await NativeBridge.guard(
      NativeBridge.downloads,
      const <Map<String, dynamic>>[],
      'downloads unavailable',
    );
    if (!mounted) return;
    setState(() => _downloadCount = items.length);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _state,
      builder: (context, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SafeArea(bottom: false, child: TopBar(title: 'Settings', onBack: widget.onBack)),
          Expanded(
            child: ListView(
              controller: widget.scrollController,
              padding: const EdgeInsets.only(bottom: 120),
              children: [
                const SectionLabel('Browsing'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      _toggle('Save browsing history', _state.history, (v) {
                        _state.setHistory(v);
                        AppToast.show(context, 'History ${v ? 'ON' : 'OFF'}');
                      }),
                      _toggle('JavaScript', _state.js, (v) {
                        _state.setJs(v);
                        // WebSettings take effect on the next load, so say so
                        // rather than let the current page look unchanged.
                        AppToast.show(
                          context,
                          'JavaScript ${v ? 'ON' : 'OFF'} — reload the page to apply',
                        );
                      }),
                      _toggle('Search suggestions', _state.suggestions, (v) {
                        _state.setSuggestions(v);
                        AppToast.show(context, 'Suggestions ${v ? 'ON' : 'OFF'}');
                      }),
                    ]),
                  ),
                ),
                const SectionLabel('Agent'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      Builder(
                        builder: (rowContext) => SettingRow(
                          label: 'Privacy profile',
                          value: _state.profileLabel,
                          valueOn: true,
                          onTap: () => _pickProfile(rowContext),
                        ),
                      ),
                      Builder(
                        builder: (rowContext) => SettingRow(
                          label: 'AI provider',
                          value: _state.providerLabel,
                          onTap: () => _pickProvider(rowContext),
                        ),
                      ),
                      Builder(
                        builder: (rowContext) => SettingRow(
                          label: 'Terminal',
                          value: _state.terminalLabel,
                          onTap: () => _pickTerminal(rowContext),
                        ),
                      ),
                    ]),
                  ),
                ),
                const SectionLabel('Data'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      SettingRow(
                        label: 'Clear browsing data',
                        onTap: () => Navigator.of(context)
                            .push(MaterialPageRoute(builder: (_) => const ClearDataScreen())),
                      ),
                      Builder(
                        builder: (rowContext) => SettingRow(
                          label: 'Download folder',
                          value: _downloadFolder,
                          valueOn: _customFolder,
                          onTap: () => _chooseDownloadFolder(rowContext),
                        ),
                      ),
                      SettingRow(
                        label: 'Downloads',
                        value: _downloadCount?.toString(),
                        onTap: () async {
                          await Navigator.of(context)
                              .push(MaterialPageRoute(builder: (_) => const DownloadsScreen()));
                          _loadDownloadCount();
                        },
                      ),
                      SettingRow(
                        label: 'Privacy dashboard',
                        onTap: () => Navigator.of(context)
                            .push(MaterialPageRoute(builder: (_) => const PrivacyScreen())),
                      ),
                      SettingRow(label: 'About', onTap: _about),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _toggle(String label, bool value, ValueChanged<bool> onChanged) {
    return SettingRow(
      label: label,
      trailing: PillToggle(value: value, onChanged: onChanged),
      onTap: () => onChanged(!value),
    );
  }

  Future<void> _pickProfile(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Privacy profile',
      selected: _state.profile,
      options: const [
        MenuOption(id: 'BALANCED', label: 'Balanced', icon: Icons.balance, tag: 'default'),
        MenuOption(id: 'STRICT', label: 'Strict', icon: Icons.shield_outlined, tag: '3P cookies blocked'),
        MenuOption(id: 'MAXIMUM', label: 'Maximum', icon: Icons.lock_outline, tag: 'JS off'),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setProfile(picked);
    if (!mounted) return;
    AppToast.show(context, 'Profile: ${_state.profileLabel}');
  }

  Future<void> _pickTerminal(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<bool>(
      context: rowContext,
      title: 'Terminal',
      selected: _state.terminal,
      options: const [
        MenuOption(id: false, label: 'Off', icon: Icons.power_settings_new),
        MenuOption(
          id: true,
          label: 'On (sandboxed)',
          icon: Icons.terminal,
          tag: 'ALLOW / CONFIRM / DENY',
        ),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setTerminal(picked);
    if (!mounted) return;
    AppToast.show(context, 'Terminal ${picked ? 'enabled' : 'disabled'}');
  }

  Future<void> _pickProvider(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'AI provider',
      selected: _state.providerId,
      options: [
        for (final p in AiProviderOption.all)
          MenuOption(id: p.id, label: p.name, icon: _providerIcon(p.id), tag: p.tag),
      ],
    );
    if (picked == null || !mounted) return;
    if (picked == 'local') {
      await _state.setProvider('local');
      if (!mounted) return;
      AppToast.show(context, 'Local provider active');
      return;
    }
    // A remote provider needs a key/base/model before it can be made active —
    // send the user to the provider screen instead of silently enabling it.
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => AiProviderScreen(initialProvider: picked)),
    );
    if (mounted) setState(() {});
  }

  static IconData _providerIcon(String id) {
    switch (id) {
      case 'gemini':
        return Icons.auto_awesome;
      case 'groq':
        return Icons.bolt;
      case 'openai':
        return Icons.hub_outlined;
      default:
        return Icons.memory;
    }
  }

  void _about() {
    showDialog<void>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: AppColors.surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text('Mr Nobody', style: AppTheme.sans(size: 16, w: FontWeight.w700)),
        content: Text(
          'A small, private, agentic browser.\n\n'
          'No ads. No tracking by Mr Nobody. No automatic browsing history.\n'
          'Tell Mr Nobody what you want from the web.',
          style: AppTheme.sans(size: 12.5, color: AppColors.textDim, height: 1.5),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: Text('OK', style: AppTheme.sans(size: 13, color: AppColors.accent, w: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}
