import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/anchored_menu.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';
import 'about_screen.dart';
import 'ai_provider_screen.dart';
import 'bookmarks_screen.dart';
import 'clear_data_screen.dart';
import 'downloads_screen.dart';
import 'design_platform_screen.dart';
import 'privacy_screen.dart';
import 'restricted_tools_screen.dart';

/// Settings (S6) — three groups, exactly as in `#v-settings`:
/// Browsing (toggles), Agent (profile / provider / terminal), Data.
///
/// Every control writes straight through to the Java core, so what the screen
/// shows is what is actually persisted.
class SettingsScreen extends StatefulWidget {
  final VoidCallback? onBack;
  final Future<void> Function()? onBeforeBrowserDataClear;
  final ScrollController? scrollController;

  /// Opens a URL in a new tab — lets the Bookmarks screen open pages without
  /// knowing anything about the tab shell.
  final void Function(String url)? onOpenUrl;

  const SettingsScreen({
    super.key,
    this.onBack,
    this.onBeforeBrowserDataClear,
    this.scrollController,
    this.onOpenUrl,
  });

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _state = AppState.instance;
  int? _downloadCount;
  int? _bookmarkCount;
  String _downloadFolder = 'Downloads (system)';
  bool _customFolder = false;
  String _proxyRoute = 'tor-orbot';
  String _proxyKind = 'http';
  String _proxyHost = '';
  int _proxyPort = 0;

  /// Null until asked: do not claim isolation without a fact.
  bool? _multiProfile;

  String get _proxyLabel {
    switch (_proxyRoute) {
      case 'direct':
        return 'Direct';
      case 'proxy':
        return 'HTTP proxy';
      case 'tor-orbot':
      default:
        return 'Orbot (Tor)';
    }
  }

  @override
  void initState() {
    super.initState();
    _state.load();
    _loadDownloadCount();
    _loadBookmarkCount();
    _loadDownloadFolder();
    _loadProxy();
    _loadCaps();
  }

  Future<void> _loadCaps() async {
    final info = await NativeBridge.guard(
      NativeBridge.engineInfo,
      const <String, dynamic>{},
      'engine info unavailable',
    );
    if (!mounted || info.isEmpty) return;
    setState(() => _multiProfile = info['multiProfile'] as bool?);
  }

  Future<void> _loadProxy() async {
    final s = await NativeBridge.guard(
      NativeBridge.getSettings,
      const <String, dynamic>{},
      'settings unavailable',
    );
    if (!mounted || s.isEmpty) return;
    setState(() {
      _proxyRoute = s['route'] as String? ?? _proxyRoute;
      _proxyKind = s['proxyKind'] as String? ?? _proxyKind;
      _proxyHost = s['proxyHost'] as String? ?? _proxyHost;
      _proxyPort = (s['proxyPort'] as num?)?.toInt() ?? _proxyPort;
    });
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
          MenuOption(
              id: 'keep',
              label: 'Choose another folder',
              icon: Icons.folder_open),
          MenuOption(
              id: 'system', label: 'Use system Downloads', icon: Icons.undo),
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

  Future<void> _loadBookmarkCount() async {
    final marks = await NativeBridge.guard(
      NativeBridge.bookmarks,
      const <Map<String, dynamic>>[],
      'bookmarks unavailable',
    );
    if (!mounted) return;
    setState(() => _bookmarkCount = marks.length);
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
      builder: (context, _) => ScreenSurface(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SafeArea(
                bottom: false,
                child: TopBar(title: 'Settings', onBack: widget.onBack)),
            Expanded(
              child: ListView(
                controller: widget.scrollController,
                padding: const EdgeInsets.only(bottom: 120),
                children: [
                  // A required release keeps nudging from here — persistently,
                  // but never by blocking the app (there is no Play Store to
                  // force an install; the choice stays the user's).
                  _requiredUpdateBanner(),
                  const SectionLabel('Browsing'),
                  AppCard(
                    child: Column(
                      children: withDividers([
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Theme',
                            value: _state.themeLabel,
                            valueOn: _state.themeId == 'warm',
                            onTap: () => _pickTheme(rowContext),
                          ),
                        ),
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
                          AppToast.show(
                              context, 'Suggestions ${v ? 'ON' : 'OFF'}');
                        }),
                        _toggle('Block ads & trackers', _state.blocking, (v) {
                          _state.setBlocking(v);
                          AppToast.show(context,
                              'Ad blocking ${v ? 'ON' : 'OFF'} — reload the page to apply');
                        }),
                        _toggle(
                            'Strip tracking parameters', _state.paramStripping,
                            (v) {
                          _state.setParamStripping(v);
                          AppToast.show(context,
                              'Parameter stripping ${v ? 'ON' : 'OFF'} — reload the page to apply');
                        }),
                      ]),
                    ),
                  ),
                  const SectionLabel('Privacy'),
                  AppCard(
                    child: Column(
                      children: withDividers([
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Privacy mode',
                            value: _state.privacyModeLabel,
                            valueOn: true,
                            onTap: () => _pickPrivacyMode(rowContext),
                          ),
                        ),
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Proxy',
                            value: _proxyLabel,
                            valueOn: _proxyRoute != 'direct',
                            onTap: () => _pickProxy(rowContext),
                          ),
                        ),
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
                        SettingRow(
                          label: 'Design platform',
                          value: 'CANVA MCP',
                          onTap: _openDesignPlatform,
                        ),
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Terminal',
                            value: _state.terminalLabel,
                            onTap: () => _pickTerminal(rowContext),
                          ),
                        ),
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Search engine',
                            value: _state.searchEngineLabel,
                            valueOn: true,
                            onTap: () => _pickSearchEngine(rowContext),
                          ),
                        ),
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Approval mode',
                            value: _state.approvalModeLabel,
                            onTap: () => _pickApprovalMode(rowContext),
                          ),
                        ),
                        const ComingSoonRow(
                          label: 'Remote worker',
                          detail: 'Runs tasks on a server for long, heavy or '
                              'background work. The app is ready; the production '
                              'server is being built.',
                          icon: Icons.cloud_outlined,
                        ),
                        const ComingSoonRow(
                          label: 'Credits & payments',
                          detail: 'Purchases, refunds and a billing ledger will '
                              'arrive once remote execution is reliable.',
                          icon: Icons.account_balance_wallet_outlined,
                        ),
                        const ComingSoonRow(
                          label: 'Figma & Adobe Express',
                          detail: 'Additional design-platform integrations are '
                              'planned after Canva.',
                          icon: Icons.design_services_outlined,
                        ),
                      ]),
                    ),
                  ),
                  const SectionLabel('App'),
                  AppCard(
                    child: Column(
                      children: withDividers([
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Updates',
                            value: _updatesValue,
                            valueOn: _state.updates.showBadge,
                            trailing: _state.updates.showBadge
                                ? _UpdateBadge(required: _state.updates.required)
                                : null,
                            onTap: _openUpdates,
                          ),
                        ),
                      ]),
                    ),
                  ),
                  const SectionLabel('Data'),
                  AppCard(
                    child: Column(
                      children: withDividers([
                        Builder(
                          builder: (rowContext) => SettingRow(
                            label: 'Data Saver',
                            value: _state.resourcePolicyLabel,
                            valueOn: _state.resourcePolicy != 'OFF',
                            onTap: () => _pickDataSaver(rowContext),
                          ),
                        ),
                        SettingRow(
                          label: 'Clear browsing data',
                          onTap: () =>
                              Navigator.of(context).push(MaterialPageRoute(
                            builder: (_) => ClearDataScreen(
                              onBeforeBrowserDataClear:
                                  widget.onBeforeBrowserDataClear,
                            ),
                          )),
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
                          label: 'Bookmarks',
                          value: _bookmarkCount?.toString(),
                          onTap: () async {
                            await Navigator.of(context).push(MaterialPageRoute(
                                builder: (_) => BookmarksScreen(
                                    onOpenUrl: widget.onOpenUrl)));
                            _loadBookmarkCount();
                          },
                        ),
                        SettingRow(
                          label: 'Downloads',
                          value: _downloadCount?.toString(),
                          onTap: () async {
                            await Navigator.of(context).push(MaterialPageRoute(
                                builder: (_) => const DownloadsScreen()));
                            _loadDownloadCount();
                          },
                        ),
                        SettingRow(
                          label: 'Privacy dashboard',
                          onTap: () => Navigator.of(context).push(
                              MaterialPageRoute(
                                  builder: (_) => const PrivacyScreen())),
                        ),
                        SettingRow(label: 'About', onTap: _about),
                      ]),
                    ),
                  ),
                  const SectionLabel('Developer'),
                  AppCard(
                    child: Column(
                      children: withDividers([
                        _toggle('Developer Tools (console + inspect)', _state.terminal, (v) async {
                          await _state.setTerminal(v);
                          if (!context.mounted) return;
                          AppToast.show(context, v ? 'DevTools ON — WebView debugging enabled, use menu > Developer Tools' : 'DevTools OFF');
                        }),
                        SettingRow(
                          label: 'Restricted tools',
                          value: 'off',
                          onTap: () => Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => const RestrictedToolsScreen(),
                            ),
                          ),
                        ),
                                                SettingRow(
                          label: 'DevTools Help',
                          value: 'how to use',
                          onTap: () {
                            showDialog<void>(
                              context: context,
                              builder: (c) => AlertDialog(
                                backgroundColor: AppColors.overlay,
                                title: Text('Developer Tools', style: AppTheme.sans(size: 16, color: AppColors.overlayInk, w: FontWeight.w700)),
                                content: SingleChildScrollView(
                                  child: Text(
                                    '''Mr Nobody DevTools:

1. In-App (menu > Developer Tools):
- Console: logs
- Network: requests
- Elements: HTML
- Storage: cookies
- Eval: JS

2. Eruda overlay:
- Full mobile DevTools

3. Remote chrome://inspect
- Enable toggle
- USB + inspect

Bundled, no CDN.''',
                                    style: AppTheme.sans(size: 12.5, color: AppColors.overlayMuted, height: 1.5),
                                  ),
                                ),
                                actions: [
                                  TextButton(onPressed: () => Navigator.pop(c), child: const Text('Got it')),
                                ],
                              ),
                            );
                          },
                        ),
                      ]),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
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

  Future<void> _pickTheme(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Theme',
      selected: _state.themeId,
      options: const [
        MenuOption(
          id: 'classic',
          label: 'Classic dark',
          icon: Icons.contrast,
          tag: 'original',
        ),
        MenuOption(
          id: 'warm',
          label: 'Warm cream',
          icon: Icons.light_mode_outlined,
          tag: 'new',
        ),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setTheme(picked);
    if (!mounted) return;
    AppToast.show(context, 'Theme: ${_state.themeLabel}');
  }

  Future<void> _pickProfile(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Privacy profile',
      selected: _state.profile,
      options: const [
        MenuOption(
            id: 'BALANCED',
            label: 'Balanced',
            icon: Icons.balance,
            tag: 'default'),
        MenuOption(
            id: 'STRICT',
            label: 'Strict',
            icon: Icons.shield_outlined,
            tag: '3P cookies blocked'),
        MenuOption(
            id: 'MAXIMUM',
            label: 'Maximum',
            icon: Icons.lock_outline,
            tag: 'JS off'),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setProfile(picked);
    if (!mounted) return;
    // The toast names a consequence the toggles now reflect, so Maximum
    // cannot look like a no-op.
    AppToast.show(
      context,
      _state.js
          ? 'Profile: ${_state.profileLabel}'
          : 'Profile: ${_state.profileLabel} — JavaScript off',
    );
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

  Future<void> _openDesignPlatform() async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const DesignPlatformScreen()),
    );
    if (mounted) setState(() {});
  }

  Future<void> _pickProvider(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'AI provider',
      selected: _state.providerId,
      options: [
        for (final p in AiProviderOption.all)
          MenuOption(
              id: p.id, label: p.name, icon: _providerIcon(p.id), tag: p.tag),
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
      MaterialPageRoute(
          builder: (_) => AiProviderScreen(initialProvider: picked)),
    );
    if (mounted) setState(() {});
  }

  Future<void> _pickPrivacyMode(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Privacy mode',
      selected: _state.privacyMode,
      options: [
        const MenuOption(
            id: 'NORMAL', label: 'Normal', icon: Icons.language, tag: 'direct'),
        MenuOption(
            id: 'PRIVATE',
            label: 'Private',
            icon: Icons.visibility_off,
            // Isolation is a property of this device's WebView, not of our
            // APK. Claiming it here when the dashboard says otherwise is
            // the overstated private-tab claim all over again.
            tag: _multiProfile == true
                ? 'isolated storage'
                : 'no history, cleared on close'),
        const MenuOption(
            id: 'NOBODY',
            label: 'Nobody',
            icon: Icons.shield_outlined,
            // The user must know their traffic enters the Tor network —
            // both for honesty and because Tor use is visible to a carrier.
            tag: 'built-in Tor / Orbot / proxy'),
      ],
    );
    if (picked == null || !mounted) return;
    final problem = await _state.setPrivacyMode(picked);
    if (!mounted) return;
    if (problem != null) {
      AppToast.show(context, problem);
    } else {
      AppToast.show(context, 'Privacy mode: ${_state.privacyModeLabel}');
    }
  }

  Future<void> _pickProxy(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Proxy route',
      selected: _proxyRoute,
      options: const [
        MenuOption(
            id: 'tor-orbot',
            label: 'Orbot (Tor)',
            icon: Icons.shield_outlined,
            tag: 'SOCKS 9050'),
        MenuOption(
            id: 'proxy',
            label: 'HTTP proxy',
            icon: Icons.swap_horiz,
            tag: 'host : port'),
        MenuOption(
            id: 'direct',
            label: 'Direct',
            icon: Icons.language,
            tag: 'no proxy'),
      ],
    );
    if (picked == null || !mounted) return;
    if (picked == 'proxy') {
      final endpoint = await _editProxyEndpoint();
      if (!mounted) return;
      if (endpoint == null) return;
    }
    final result = await NativeBridge.guard(
      () => NativeBridge.setProxy(
        kind: _proxyKind,
        host: _proxyHost.isEmpty ? null : _proxyHost,
        port: _proxyPort == 0 ? null : _proxyPort,
        route: picked,
      ),
      const <String, dynamic>{},
      'could not apply proxy',
    );
    if (!mounted) return;
    setState(() => _proxyRoute = picked);
    final problem = result['problem'] as String?;
    if (problem != null && problem.isNotEmpty) {
      AppToast.show(context, problem);
    } else {
      AppToast.show(context, 'Proxy: $_proxyLabel');
    }
  }

  /// Host and port for the HTTP proxy. Empty host is not a configured proxy.
  Future<bool?> _editProxyEndpoint() async {
    final hostCtrl = TextEditingController(text: _proxyHost);
    final portCtrl =
        TextEditingController(text: _proxyPort == 0 ? '8080' : '$_proxyPort');
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: AppColors.overlay,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppColors.isWarm ? 24 : 16),
        ),
        title: Text(
          'HTTP proxy',
          style: AppTheme.sans(
            size: AppColors.isWarm ? 17 : 16,
            color: AppColors.overlayInk,
            w: FontWeight.w700,
          ),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: hostCtrl,
              autofocus: true,
              style: AppTheme.mono(
                size: 13,
                color:
                    AppColors.isWarm ? AppColors.overlayInk : AppColors.textDim,
              ),
              cursorColor:
                  AppColors.isWarm ? AppColors.overlayInk : AppColors.accent,
              decoration: InputDecoration(
                filled: AppColors.isWarm,
                fillColor: AppColors.overlaySelected,
                border: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayLine),
                      )
                    : null,
                enabledBorder: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayLine),
                      )
                    : null,
                focusedBorder: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayInk),
                      )
                    : null,
                labelText: 'Host',
                labelStyle: AppTheme.sans(
                  size: 12,
                  color: AppColors.isWarm
                      ? AppColors.overlayFaint
                      : AppColors.textMuted,
                ),
                hintText: '127.0.0.1',
                hintStyle: AppTheme.mono(
                  size: 12,
                  color: AppColors.overlayFaint,
                ),
              ),
            ),
            if (AppColors.isWarm) const SizedBox(height: 12),
            TextField(
              controller: portCtrl,
              keyboardType: TextInputType.number,
              style: AppTheme.mono(
                size: 13,
                color:
                    AppColors.isWarm ? AppColors.overlayInk : AppColors.textDim,
              ),
              cursorColor:
                  AppColors.isWarm ? AppColors.overlayInk : AppColors.accent,
              decoration: InputDecoration(
                filled: AppColors.isWarm,
                fillColor: AppColors.overlaySelected,
                border: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayLine),
                      )
                    : null,
                enabledBorder: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayLine),
                      )
                    : null,
                focusedBorder: AppColors.isWarm
                    ? OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: AppColors.overlayInk),
                      )
                    : null,
                labelText: 'Port',
                labelStyle: AppTheme.sans(
                  size: 12,
                  color: AppColors.isWarm
                      ? AppColors.overlayFaint
                      : AppColors.textMuted,
                ),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: Text(
              'Cancel',
              style: AppTheme.sans(
                size: 13,
                color: AppColors.overlayMuted,
                w: AppColors.isWarm ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
          if (AppColors.isWarm)
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.overlayInk,
                foregroundColor: AppColors.overlay,
              ),
              onPressed: () => Navigator.pop(c, true),
              child: const Text('Save'),
            )
          else
            TextButton(
              onPressed: () => Navigator.pop(c, true),
              child: Text(
                'Save',
                style: AppTheme.sans(
                  size: 13,
                  color: AppColors.accent,
                  w: FontWeight.w600,
                ),
              ),
            ),
        ],
      ),
    );
    final host = hostCtrl.text.trim();
    final port = int.tryParse(portCtrl.text.trim()) ?? 0;
    hostCtrl.dispose();
    portCtrl.dispose();
    if (!mounted) return null;
    if (ok != true) return null;
    if (host.isEmpty) {
      AppToast.show(context, 'A proxy needs a host.');
      return null;
    }
    setState(() {
      _proxyHost = host;
      _proxyPort = port;
      _proxyKind = 'http';
    });
    return true;
  }

  Future<void> _pickSearchEngine(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Search engine',
      selected: _state.searchEngine,
      options: [
        for (final e in AppState.searchEngines.entries)
          MenuOption(id: e.value, label: e.key, icon: Icons.search),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setSearchEngine(picked);
    if (!mounted) return;
    AppToast.show(context, 'Search engine: ${_state.searchEngineLabel}');
  }

  Future<void> _pickApprovalMode(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Approval mode',
      selected: _state.approvalMode,
      options: [
        for (final m in AppState.approvalModes)
          MenuOption(
              id: m,
              label: AppState.approvalLabels[m] ?? m,
              icon: Icons.gpp_maybe_outlined),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setApprovalMode(picked);
    if (!mounted) return;
    AppToast.show(context, 'Approval: ${_state.approvalModeLabel}');
  }

  Future<void> _pickDataSaver(BuildContext rowContext) async {
    final picked = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Data Saver',
      selected: _state.resourcePolicy,
      options: const [
        MenuOption(
            id: 'OFF',
            label: 'Off',
            icon: Icons.wifi,
            tag: 'nothing restricted'),
        MenuOption(
            id: 'BALANCED',
            label: 'Balanced',
            icon: Icons.pause_circle_outline,
            tag: 'autoplay off'),
        MenuOption(
            id: 'AGGRESSIVE',
            label: 'Aggressive',
            icon: Icons.image_not_supported_outlined,
            tag: '+ images off'),
        MenuOption(
            id: 'EXTREME',
            label: 'Extreme',
            icon: Icons.offline_bolt,
            tag: '+ no caching'),
      ],
    );
    if (picked == null || !mounted) return;
    await _state.setResourcePolicy(picked);
    if (!mounted) return;
    AppToast.show(context, 'Data Saver: ${_state.resourcePolicyLabel}');
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
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => AboutScreen(onOpenUrl: widget.onOpenUrl),
    ));
  }

  // ------------------------------------------------------- update notifications

  String get _updatesValue {
    final u = _state.updates;
    if (u.showBadge) return 'v${u.latestVersion}';
    if (u.hasChecked) return 'Up to date';
    return '—';
  }

  /// The persistent nudge for a required release. Returns a zero-height
  /// widget (not null) for an optional or dismissed update, so the row
  /// above stays the first child; those live quietly in the Updates row.
  Widget _requiredUpdateBanner() {
    final u = _state.updates;
    if (!(u.updateAvailable && !u.dismissed && u.required)) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      child: Material(
        color: Colors.transparent,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.warning.withOpacity(0.12),
            border: Border.all(color: AppColors.warning.withOpacity(0.55)),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            children: [
              const Icon(Icons.campaign_outlined,
                  size: 18, color: AppColors.warning),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Required update v${u.latestVersion} available',
                  style: AppTheme.sans(
                      size: 12.5, color: AppColors.warningInk, w: FontWeight.w600),
                ),
              ),
              TextButton(
                onPressed: _openUpdates,
                child: Text('View',
                    style: AppTheme.sans(
                        size: 12.5,
                        color: AppColors.warningInk,
                        w: FontWeight.w700)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _openUpdates() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.overlay,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => _UpdateSheet(onOpenUrl: widget.onOpenUrl),
    );
  }
}

/// The dot next to the Updates row: accent for an optional release,
/// warning colour when the release is required.
class _UpdateBadge extends StatelessWidget {
  final bool required;
  const _UpdateBadge({required this.required});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 8,
      height: 8,
      margin: const EdgeInsets.only(right: 10),
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: required ? AppColors.warning : AppColors.accent,
      ),
    );
  }
}

/// How long ago an epoch-millis timestamp was, in the calm register the rest
/// of the settings screen uses.
String _relativeTime(int epochMillis) {
  if (epochMillis <= 0) return 'never';
  final age =
      DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(epochMillis));
  if (age.inSeconds < 60) return 'just now';
  if (age.inMinutes < 60) return '${age.inMinutes} min ago';
  if (age.inHours < 24) return '${age.inHours} h ago';
  return '${age.inDays} d ago';
}

/// The update detail sheet: what changed, whether it is optional or required,
/// the integrity data, and the two choices the user actually has.
///
/// Reads live from [AppState] so a "check again" or a dismissal repaints it
/// without the caller re-pushing anything.
class _UpdateSheet extends StatefulWidget {
  final void Function(String url)? onOpenUrl;
  const _UpdateSheet({this.onOpenUrl});

  @override
  State<_UpdateSheet> createState() => _UpdateSheetState();
}

class _UpdateSheetState extends State<_UpdateSheet> {
  bool _busy = false;

  Future<void> _checkNow() async {
    if (_busy) return;
    setState(() => _busy = true);
    await AppState.instance.checkUpdatesNow();
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _dismiss() async {
    if (_busy) return;
    setState(() => _busy = true);
    await AppState.instance.dismissUpdate();
    if (!mounted) return;
    setState(() => _busy = false);
    Navigator.of(context).pop();
  }

  void _update() {
    final u = AppState.instance.updates;
    final url = u.downloadUrl;
    if (url.isEmpty) {
      AppToast.show(context, 'No download link in the release metadata yet.');
      return;
    }
    final open = widget.onOpenUrl;
    if (open == null) {
      AppToast.show(context, 'The in-app browser is unavailable right now.');
      return;
    }
    // Close the sheet BEFORE navigating: pushing the browser first would
    // leave it on top, so the pop() that follows would dismiss the browser
    // instead of the sheet — the tap would appear to do nothing.
    Navigator.of(context).pop();
    open(url);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: AppState.instance,
      builder: (context, _) {
        final u = AppState.instance.updates;
        return Padding(
          padding: EdgeInsets.fromLTRB(
              20, 20, 20, MediaQuery.of(context).viewInsets.bottom + 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Updates',
                style: AppTheme.sans(
                    size: 17,
                    color: AppColors.overlayInk,
                    w: FontWeight.w700),
              ),
              const SizedBox(height: 14),
              if (u.showBadge) ...[
                Row(
                  children: [
                    Text('v${u.latestVersion}',
                        style: AppTheme.mono(
                            size: 16,
                            color: AppColors.overlayInk,
                            w: FontWeight.w700)),
                    const SizedBox(width: 10),
                    _chip(
                      u.required ? 'Required' : 'Optional',
                      u.required ? AppColors.warning : AppColors.accent,
                    ),
                  ],
                ),
                if (u.installedVersion.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      'Installed: v${u.installedVersion}',
                      style: AppTheme.mono(
                          size: 11, color: AppColors.overlayMuted),
                    ),
                  ),
                if (u.releaseNotes.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(
                    u.releaseNotes,
                    style: AppTheme.sans(
                        size: 13.5,
                        color: AppColors.overlayMuted,
                        w: FontWeight.w400,
                        height: 1.5),
                  ),
                ],
                const SizedBox(height: 12),
                _metaLine(context, u),
                if (u.sha256.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      'sha256  ${u.sha256.substring(0, 16)}…',
                      style: AppTheme.mono(
                          size: 10.5, color: AppColors.overlayFaint),
                    ),
                  ),
                const SizedBox(height: 18),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.overlayInk,
                    foregroundColor: AppColors.overlay,
                    padding: const EdgeInsets.symmetric(vertical: 13),
                  ),
                  onPressed: _busy ? null : _update,
                  child: const Text('Update'),
                ),
                TextButton(
                  onPressed: _busy ? null : _dismiss,
                  child: Text(
                    'Remind me later',
                    style: AppTheme.sans(
                        size: 13,
                        color: AppColors.overlayMuted,
                        w: FontWeight.w600),
                  ),
                ),
              ] else if (u.hasChecked) ...[
                Row(
                  children: [
                    Text(
                      u.installedVersion.isEmpty
                          ? 'Up to date'
                          : 'v${u.installedVersion} — up to date',
                      style: AppTheme.sans(
                          size: 14,
                          color: AppColors.overlayInk,
                          w: FontWeight.w600),
                    ),
                    const SizedBox(width: 8),
                    const Icon(Icons.check_circle,
                        size: 16, color: AppColors.success),
                  ],
                ),
                const SizedBox(height: 12),
                _metaLine(context, u),
                const SizedBox(height: 18),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.overlayInk,
                    foregroundColor: AppColors.overlay,
                    padding: const EdgeInsets.symmetric(vertical: 13),
                  ),
                  onPressed: _busy ? null : _checkNow,
                  child: Text(_busy ? 'Checking…' : 'Check again'),
                ),
              ] else ...[
                Text(
                  u.networkFailed
                      ? 'The update server could not be reached, so nothing has been checked yet. Normal browsing is unaffected.'
                      : 'No update check has completed yet.',
                  style: AppTheme.sans(
                      size: 13.5,
                      color: AppColors.overlayMuted,
                      w: FontWeight.w400,
                      height: 1.5),
                ),
                const SizedBox(height: 18),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.overlayInk,
                    foregroundColor: AppColors.overlay,
                    padding: const EdgeInsets.symmetric(vertical: 13),
                  ),
                  onPressed: _busy ? null : _checkNow,
                  child: Text(_busy ? 'Checking…' : 'Check for updates'),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Widget _metaLine(BuildContext context, UpdateStatus u) {
    final bits = <String>[];
    if (u.publishedAt.isNotEmpty) bits.add('published ${u.publishedAt}');
    bits.add('checked ${_relativeTime(u.lastCheckedAt)}');
    if (u.networkFailed && u.hasChecked) bits.add('server unreachable');
    return Text(
      bits.join('  ·  '),
      style: AppTheme.mono(size: 10.5, color: AppColors.overlayFaint),
    );
  }
}

Widget _chip(String label, Color color) {
  return Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      border: Border.all(color: color),
      borderRadius: BorderRadius.circular(99),
    ),
    child: Text(
      label.toUpperCase(),
      style: AppTheme.mono(size: 9.5, color: color, w: FontWeight.w700),
    ),
  );
}
