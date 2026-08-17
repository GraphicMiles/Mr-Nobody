import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../bridge/native_bridge.dart';

/// Privacy dashboard (S4) — real counters from the Java filter engine.
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
    try {
      final stats = await NativeBridge.privacyStats();
      final history = await NativeBridge.isHistoryEnabled();
      if (!mounted) return;
      setState(() { _stats = stats; _history = history; });
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final s = _stats;
    return Scaffold(
      body: PanelShell(
        title: 'Privacy',
        onBack: () => Navigator.of(context).pop(),
        children: [
          const SectionLabel('This page'),
          _Card([
            MetricRow('Privacy score', s == null ? '—' : '${s['score']} / 100'),
            const Divider(),
            MetricRow('Ads blocked', s == null ? '—' : '${s['pageAds']}'),
            const Divider(),
            MetricRow('Trackers blocked', s == null ? '—' : '${s['pageTrackers']}'),
          ]),
          const SectionLabel('Today'),
          _Card([
            MetricRow('Ads blocked', s == null ? '—' : '${s['todayAds']}'),
            const Divider(),
            MetricRow('Trackers blocked', s == null ? '—' : '${s['todayTrackers']}'),
          ]),
          const SectionLabel('Cookies'),
          const _Card([MetricRow('Third-party', 'Blocked', dim: true)]),
          const SectionLabel('History'),
          _Card([MetricRow('Saved locally', _history ? 'ON' : 'OFF', dim: true)]),
          const Padding(
            padding: EdgeInsets.all(20),
            child: Center(child: Text('all counts stay on-device', style: TextStyle(fontSize: 10.5, color: AppColors.textFaint))),
          ),
        ],
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final List<Widget> children;
  const _Card(this.children);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: AppCard(child: Column(children: children)),
    );
  }
}
