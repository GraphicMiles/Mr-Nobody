import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';

/// Privacy dashboard (S4) — real counters from the Java filter engine, split
/// into this page / today / cookies / history, with the on-device promise at
/// the bottom. Matches `#v-privacy`.
class PrivacyScreen extends StatefulWidget {
  const PrivacyScreen({super.key});

  @override
  State<PrivacyScreen> createState() => _PrivacyScreenState();
}

class _PrivacyScreenState extends State<PrivacyScreen> {
  Map<String, dynamic>? _stats;
  Map<String, dynamic> _engine = const {};
  Map<String, dynamic> _settings = const {};
  bool _history = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final stats = await NativeBridge.guard(
      NativeBridge.privacyStats,
      const <String, dynamic>{},
      'privacy stats unavailable',
    );
    final history = await NativeBridge.guard(
      NativeBridge.isHistoryEnabled,
      false,
      'history flag unavailable',
    );
    final engine = await NativeBridge.guard(
      NativeBridge.engineInfo,
      const <String, dynamic>{},
      'engine info unavailable',
    );
    final settings = await NativeBridge.guard(
      NativeBridge.getSettings,
      const <String, dynamic>{},
      'settings unavailable',
    );
    if (!mounted) return;
    setState(() {
      _stats = stats.isEmpty ? null : stats;
      _history = history;
      _engine = engine;
      _settings = settings;
    });
  }

  String _v(String key, {String suffix = ''}) {
    final s = _stats;
    if (s == null || s[key] == null) return '—';
    return '${s[key]}$suffix';
  }

  /// A capability is only "Available" when the device actually reports it.
  ///
  /// Unknown is shown as a dash rather than as "Unavailable": failing to ask
  /// and being told no are different, and only one of them is a fact.
  String _cap(String key) {
    final v = _engine[key];
    if (v == null) return '—';
    return v == true ? 'Available' : 'Not on this device';
  }

  bool _capMissing(String key) => _engine[key] == false;

  /// Fingerprint defence needs two things: a WebView that can run a
  /// document-start script, and the setting switched on. Reporting only the
  /// first would say "Available" while nothing is running -- the toggle is off
  /// by default and only the Strict and Maximum profiles turn it on, so that
  /// is the common case, not an edge one.
  String get _fingerprintState {
    final supported = _engine['documentStartScript'];
    if (supported == null) return '—';
    if (supported != true) return 'Not on this device';
    final on = _settings['fingerprint'];
    if (on == null) return '—';
    return on == true ? 'On' : 'Off — raise privacy profile';
  }

  bool get _anyCapabilityMissing =>
      _capMissing('multiProfile') ||
      _capMissing('documentStartScript') ||
      _capMissing('proxyOverride');

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Privacy',
        onBack: () => Navigator.of(context).pop(),
        overlay: const DebugOverlay(bottomInset: 20),
        children: [
          const SectionLabel('This page'),
          AppCard(
            child: Column(
              children: withDividers([
                MetricRow('Privacy score', _v('score', suffix: ' / 100')),
                MetricRow('Ads blocked', _v('pageAds')),
                MetricRow('Trackers blocked', _v('pageTrackers')),
              ]),
            ),
          ),
          const SectionLabel('Today'),
          AppCard(
            child: Column(
              children: withDividers([
                MetricRow('Ads blocked', _v('todayAds')),
                MetricRow('Trackers blocked', _v('todayTrackers')),
              ]),
            ),
          ),
          const SectionLabel('Cookies'),
          const AppCard(child: MetricRow('Third-party', 'Blocked', dim: true)),
          const SectionLabel('History'),
          AppCard(child: MetricRow('Saved locally', _history ? 'ON' : 'OFF', dim: true)),
          const SectionLabel('Web engine'),
          AppCard(
            child: Column(
              children: withDividers([
                MetricRow('Engine', _engine['engine'] as String? ?? '—', dim: true),
                MetricRow('Isolated private tabs', _cap('multiProfile'), dim: true),
                MetricRow('Fingerprint defence', _fingerprintState, dim: true),
                MetricRow('Proxy / Tor routing', _cap('proxyOverride'), dim: true),
              ]),
            ),
          ),
          if (_anyCapabilityMissing)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 10, 20, 0),
              child: Text(
                'Some protections depend on the Android System WebView installed '
                'on this device, not on Mr Nobody. Where one says it is not '
                'available, it is genuinely not running — updating Android '
                'System WebView usually enables it.',
                style: AppTheme.mono(
                    size: 10, color: AppColors.textMuted, height: 1.5),
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 14),
            child: Center(
              child: Text(
                'all counts stay on-device',
                style: AppTheme.mono(size: 10.5, color: AppColors.textMuted, height: 1.5),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
