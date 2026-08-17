import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Rounded monochrome card (hairline border, no shadow).
class AppCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  const AppCard({super.key, required this.child, this.padding = EdgeInsets.zero});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.line),
      ),
      child: child,
    );
  }
}

/// Uppercase mono section label.
class SectionLabel extends StatelessWidget {
  final String text;
  const SectionLabel(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
      child: Text(
        text.toUpperCase(),
        style: AppTheme.mono(size: 10, color: AppColors.textFaint, w: FontWeight.w600),
      ),
    );
  }
}

/// Key/value metric row (key left, mono value right).
class MetricRow extends StatelessWidget {
  final String label;
  final String value;
  final bool dim;
  const MetricRow(this.label, this.value, {super.key, this.dim = false});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
      child: Row(
        children: [
          Expanded(
            child: Text(label, style: AppTheme.sans(size: 13, color: AppColors.textDim)),
          ),
          Text(
            value,
            style: AppTheme.mono(
              size: dim ? 11 : 15,
              color: dim ? AppColors.textFaint : AppColors.text,
              w: dim ? FontWeight.w500 : FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen scaffold with a back-chevron header + scrollable body.
class PanelShell extends StatelessWidget {
  final String title;
  final VoidCallback onBack;
  final List<Widget> children;
  const PanelShell({super.key, required this.title, required this.onBack, required this.children});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _header(context),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.only(bottom: 24),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
          ),
        ),
      ],
    );
  }

  Widget _header(BuildContext context) {
    return Container(
      color: AppColors.surface,
      padding: EdgeInsets.only(
        left: 8,
        top: 8 + MediaQuery.of(context).padding.top,
        right: 12,
        bottom: 8,
      ),
      child: Row(
        children: [
          IconButton(
            onPressed: onBack,
            icon: const Icon(Icons.chevron_left, color: AppColors.textDim, size: 26),
          ),
          Text(title, style: AppTheme.sans(size: 16, w: FontWeight.w700)),
        ],
      ),
    );
  }
}

/// A rounded action button (ghost or solid). Self-sized; wrap in Expanded/SizedBox
/// at the call site for full-width or equal-split layouts.
class ActionButton extends StatelessWidget {
  final String label;
  final bool solid;
  final VoidCallback onTap;
  const ActionButton(this.label, {super.key, this.solid = false, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: double.infinity,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: solid ? AppColors.accent : AppColors.surface,
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: AppColors.lineStrong),
        ),
        child: Text(
          label,
          style: AppTheme.sans(
            size: 13,
            w: FontWeight.w700,
            color: solid ? AppColors.accentInk : AppColors.textDim,
          ),
        ),
      ),
    );
  }
}
