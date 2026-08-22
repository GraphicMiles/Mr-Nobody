import 'dart:async';

import 'package:flutter/material.dart';

import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

class DesignPlatformScreen extends StatefulWidget {
  const DesignPlatformScreen({super.key});

  @override
  State<DesignPlatformScreen> createState() => _DesignPlatformScreenState();
}

class _DesignPlatformScreenState extends State<DesignPlatformScreen>
    with WidgetsBindingObserver {
  Map<String, dynamic> _status = const {};
  List<Map<String, dynamic>> _tools = const [];
  String _toolError = '';
  bool _loading = true;
  bool _checking = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    final status = await NativeBridge.guard(
      NativeBridge.canvaMcpStatus,
      const <String, dynamic>{},
      'Canva MCP status unavailable',
    );
    if (!mounted) return;
    setState(() {
      _status = status;
      _loading = false;
    });
    if (status['connected'] == true) _poll?.cancel();
  }

  Future<void> _connect() async {
    final ok = await NativeBridge.guard(
      NativeBridge.connectCanvaMcp,
      false,
      'Could not start Canva sign-in',
    );
    if (!mounted || !ok) return;
    AppToast.show(context, 'Finish authorization in your browser');
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 2), (_) => _load());
  }

  Future<void> _disconnect() async {
    await NativeBridge.guard(
      NativeBridge.disconnectCanvaMcp,
      false,
      'Could not disconnect Canva',
    );
    _tools = const [];
    await _load();
  }

  Future<void> _verify() async {
    setState(() {
      _checking = true;
      _toolError = '';
    });
    final response = await NativeBridge.guard(
      NativeBridge.canvaMcpTools,
      const <String, dynamic>{},
      'Could not list Canva tools',
    );
    if (!mounted) return;
    setState(() {
      _checking = false;
      _tools = ((response['tools'] as List?) ?? const [])
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();
      _toolError = response['error'] as String? ?? '';
    });
  }

  @override
  Widget build(BuildContext context) {
    final configured = _status['configured'] == true;
    final connected = _status['connected'] == true;
    final error = _status['error'] as String? ?? '';
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Design platform',
        onBack: () => Navigator.of(context).pop(),
        children: [
          const SectionLabel('Canva MCP'),
          AppCard(
            child: Column(
              children: withDividers([
                SettingRow(
                  label: 'Official remote MCP',
                  value: connected
                      ? 'CONNECTED'
                      : configured
                          ? 'NOT CONNECTED'
                          : 'BUILD SETUP REQUIRED',
                  valueOn: connected,
                ),
                const SettingRow(
                  label: 'Transport',
                  value: 'STREAMABLE HTTP',
                  valueOn: true,
                ),
                SettingRow(
                  label: 'Authentication',
                  value: 'PER-USER OAUTH + PKCE',
                  valueOn: configured,
                ),
                const SettingRow(
                  label: 'Status',
                  value: 'SUSPENDED',
                ),
              ]),
            ),
          ),
          const SectionLabel('More design platforms'),
          AppCard(
            child: Column(
              children: withDividers([
                const ComingSoonRow(
                  label: 'Figma',
                  detail: 'Planned after Canva integration is live.',
                  icon: Icons.design_services_outlined,
                ),
                const ComingSoonRow(
                  label: 'Adobe Express',
                  detail: 'Planned after Canva integration is live.',
                  icon: Icons.palette_outlined,
                ),
              ]),
            ),
          ),
          if (_loading)
            const EmptyNote('Checking Canva MCP…')
          else if (!configured)
            Padding(
              padding: const EdgeInsets.all(18),
              child: Text(
                'Live Canva access is disabled until the build supplies an approved HTTPS '
                'CIMD client URL. The redirect to place in that metadata and Canva’s '
                'allowlist is:\n\n${_status['redirectUri'] ?? ''}',
                style: AppTheme.sans(
                    size: 11.5, color: AppColors.textDim, height: 1.55),
              ),
            )
          else ...[
            Padding(
              padding: const EdgeInsets.all(16),
              child: ActionButton(
                connected ? 'Disconnect Canva' : 'Connect Canva',
                solid: !connected,
                onTap: connected ? _disconnect : _connect,
              ),
            ),
            if (connected)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: ActionButton(
                  _checking ? 'Checking tools…' : 'Verify MCP tools',
                  onTap: _checking ? () {} : _verify,
                ),
              ),
          ],
          if (error.isNotEmpty || _toolError.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
              child: Text(
                _toolError.isNotEmpty ? _toolError : error,
                style: AppTheme.sans(
                    size: 11.5, color: AppColors.textDim, height: 1.5),
              ),
            ),
          if (_tools.isNotEmpty) ...[
            const SectionLabel('Discovered tools'),
            AppCard(
              child: Column(
                children: withDividers([
                  for (final tool in _tools)
                    SettingRow(
                      label: tool['name'] as String? ?? '',
                      value: 'AVAILABLE',
                      valueOn: true,
                    ),
                ]),
              ),
            ),
          ],
          Padding(
            padding: const EdgeInsets.all(18),
            child: Text(
              'Canva credentials are encrypted on this device and never enter AI prompts. '
              'Draft creation, creative approval, and final export remain separate gates. '
              'Canva is a compatible service; this app does not claim Canva endorsement.',
              style: AppTheme.sans(
                  size: 10.5, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
