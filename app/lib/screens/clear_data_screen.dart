import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Clear data (S7) — checkbox rows + Cancel / Clear buttons.
class ClearDataScreen extends StatefulWidget {
  const ClearDataScreen({super.key});

  @override
  State<ClearDataScreen> createState() => _ClearDataScreenState();
}

class _ClearDataScreenState extends State<ClearDataScreen> {
  final _checks = [true, true, true, false, false, false];
  static const _labels = ['History', 'Cookies', 'Cache', 'Site data', 'Task state', 'Download workspace'];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: PanelShell(
        title: 'Clear data',
        onBack: () => Navigator.of(context).pop(),
        children: [
          const SectionLabel('Clear browsing data'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              child: Column(
                children: [
                  for (var i = 0; i < _labels.length; i++) ...[
                    _row(i),
                    if (i != _labels.length - 1) const Divider(),
                  ],
                ],
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                ActionButton('Cancel', solid: false, onTap: () => Navigator.of(context).pop()),
                const SizedBox(width: 8),
                ActionButton('Clear data', solid: true, onTap: () {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Data cleared'), duration: Duration(seconds: 1)));
                }),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(int i) {
    return InkWell(
      onTap: () => setState(() => _checks[i] = !_checks[i]),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        child: Row(
          children: [
            Expanded(child: Text(_labels[i], style: AppTheme.sans(size: 13))),
            Icon(
              _checks[i] ? Icons.check_box : Icons.check_box_outline_blank,
              size: 20,
              color: _checks[i] ? AppColors.accent : AppColors.textFaint,
            ),
          ],
        ),
      ),
    );
  }
}
