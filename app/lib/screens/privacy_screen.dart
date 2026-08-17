import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Privacy dashboard (S4) — This page / Today / Cookies / History metric cards.
class PrivacyScreen extends StatelessWidget {
  const PrivacyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: PanelShell(
        title: 'Privacy',
        onBack: () => Navigator.of(context).pop(),
        children: const [
          SectionLabel('This page'),
          _Card([
            MetricRow('Privacy score', '92 / 100'),
            Divider(),
            MetricRow('Ads blocked', '8'),
            Divider(),
            MetricRow('Trackers blocked', '4'),
          ]),
          SectionLabel('Today'),
          _Card([
            MetricRow('Ads blocked', '183'),
            Divider(),
            MetricRow('Trackers blocked', '47'),
          ]),
          SectionLabel('Cookies'),
          _Card([MetricRow('Third-party', 'Blocked', dim: true)]),
          SectionLabel('History'),
          _Card([MetricRow('Saved locally', 'OFF', dim: true)]),
          Padding(
            padding: EdgeInsets.all(20),
            child: Center(
              child: Text(
                'all counts stay on-device',
                style: TextStyle(fontSize: 10.5, color: AppColors.textFaint),
              ),
            ),
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
