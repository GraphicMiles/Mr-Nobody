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
    if (!mounted) return;
    setState(() {
      _stats = stats.isEmpty ? null : stats;
      _history = history;
    });
  }

  String _v(String key, {String suffix = ''}) {
    final s = _stats;
    if (s == null || s[key] == null) return '—';
    return '${s[key]}$suffix';
  }

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
