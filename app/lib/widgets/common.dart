import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Rounded monochrome card — `.card` in the wireframe: surface tier 1, hairline
/// border, 16px radius, 16px horizontal inset.
class AppCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final EdgeInsets margin;

  const AppCard({
    super.key,
    required this.child,
    this.padding = EdgeInsets.zero,
    this.margin = const EdgeInsets.symmetric(horizontal: AppSpacing.cardInset),
  });

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
      width: double.infinity,
      margin: margin,
      padding: padding,
      decoration: BoxDecoration(
        color: AppColors.isWarm ? null : AppColors.surface,
        gradient: AppColors.isWarm
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.surface2, AppColors.surface],
              )
            : null,
        borderRadius: BorderRadius.circular(AppColors.isWarm ? 20 : 16),
        border: Border.all(color: AppColors.line),
      ),
      child: child,
    );
  }
}

/// Hairline divider used between rows inside a card.
class RowDivider extends StatelessWidget {
  const RowDivider({super.key});

  @override
  Widget build(BuildContext context) =>
      Divider(height: 1, thickness: 1, color: AppColors.line);
}

/// `.section-label` — uppercase mono, wide tracking, muted.
class SectionLabel extends StatelessWidget {
  final String text;
  const SectionLabel(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 10),
      child: Text(
        text.toUpperCase(),
        style: AppTheme.mono(
          size: 10.5,
          color: AppColors.textMuted,
          w: FontWeight.w600,
          letterSpacing: 1.26,
        ),
      ),
    );
  }
}

/// `.metric` — label left, mono value right. [dim] renders the small muted
/// variant used for "Blocked" / "OFF".
class MetricRow extends StatelessWidget {
  final String label;
  final String value;
  final bool dim;

  const MetricRow(this.label, this.value, {super.key, this.dim = false});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      child: Row(
        children: [
          Expanded(
              child: Text(label,
                  style: AppTheme.sans(size: 12.5, color: AppColors.textDim))),
          Text(
            value,
            style: AppTheme.mono(
              size: dim ? 11 : 15,
              color: dim ? AppColors.textMuted : AppColors.text,
              w: dim ? FontWeight.w500 : FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

enum ChipTone { running, done, waiting }

/// `.chip` — mono micro-label. Running is the filled accent pill.
class StatusChip extends StatelessWidget {
  final String label;
  final ChipTone tone;

  const StatusChip(this.label, {super.key, this.tone = ChipTone.running});

  @override
  Widget build(BuildContext context) {
    final Color bg;
    final Color fg;
    Border? border;
    switch (tone) {
      case ChipTone.running:
        bg = AppColors.accent;
        fg = AppColors.accentInk;
        break;
      case ChipTone.done:
        bg = AppColors.dim;
        fg = AppColors.textDim;
        break;
      case ChipTone.waiting:
        bg = Colors.transparent;
        fg = AppColors.textDim;
        border = Border.all(color: AppColors.lineStrong);
        break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
          color: bg, borderRadius: BorderRadius.circular(999), border: border),
      child: Text(label,
          style: AppTheme.mono(size: 9.5, color: fg, w: FontWeight.w600)),
    );
  }
}

/// `.progress` — 3px track with an accent fill.
class ProgressBar extends StatelessWidget {
  /// 0..1, or null when the total is genuinely unknown.
  ///
  /// Null draws the indeterminate sweep. A server that never sends a length
  /// used to produce `value: 0.0` here, which is a confident claim that no
  /// progress has been made — so a download that was running perfectly well
  /// showed an empty bar until the instant it finished. Not knowing the size
  /// and having made no progress are different facts and must not share a
  /// picture.
  final double? value;
  const ProgressBar(this.value, {super.key});

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(2),
      child: LinearProgressIndicator(
        value: value?.clamp(0.0, 1.0),
        minHeight: 3,
        backgroundColor: AppColors.surface2,
        valueColor: AlwaysStoppedAnimation(AppColors.accent),
      ),
    );
  }
}

/// `.topbar` — 34px circular back button + title.
class TopBar extends StatelessWidget {
  final String title;
  final VoidCallback? onBack;
  final Widget? trailing;

  const TopBar({super.key, required this.title, this.onBack, this.trailing});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 6),
      child: Row(
        children: [
          if (onBack != null) ...[
            GestureDetector(
              onTap: onBack,
              behavior: HitTestBehavior.opaque,
              child: Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: AppColors.line),
                ),
                child:
                    Icon(Icons.arrow_back, size: 15, color: AppColors.textDim),
              ),
            ),
            const SizedBox(width: 10),
          ],
          Text(title, style: AppTheme.sans(size: 16, w: FontWeight.w700)),
          const Spacer(),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

/// A drill-in screen: top bar + scrollable body on the app background.
class PanelShell extends StatelessWidget {
  final String title;
  final VoidCallback onBack;
  final List<Widget> children;
  final Widget? overlay;

  const PanelShell({
    super.key,
    required this.title,
    required this.onBack,
    required this.children,
    this.overlay,
  });

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Positioned.fill(
          child: DecoratedBox(decoration: AppTheme.backdrop),
        ),
        Column(
          children: [
            SafeArea(
                bottom: false, child: TopBar(title: title, onBack: onBack)),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.only(bottom: 28),
                children: children,
              ),
            ),
          ],
        ),
        if (overlay != null) Positioned.fill(child: overlay!),
      ],
    );
  }
}

/// `.cta` — pill button, ghost or solid.
class ActionButton extends StatelessWidget {
  final String label;
  final bool solid;
  final VoidCallback onTap;

  const ActionButton(this.label,
      {super.key, this.solid = false, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        width: double.infinity,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: solid ? AppColors.accent : AppColors.surface,
          borderRadius: BorderRadius.circular(999),
          border: solid ? null : Border.all(color: AppColors.line),
        ),
        child: Text(
          label,
          style: AppTheme.sans(
            size: 13,
            w: FontWeight.w600,
            color: solid ? AppColors.accentInk : AppColors.textDim,
          ),
        ),
      ),
    );
  }
}

/// `.toggle` — 36x20 pill switch (not Material's, which is far too loud for
/// this design language).
class PillToggle extends StatelessWidget {
  final bool value;
  final ValueChanged<bool>? onChanged;

  const PillToggle({super.key, required this.value, this.onChanged});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onChanged == null ? null : () => onChanged!(!value),
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        width: 36,
        height: 20,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: value ? AppColors.accent : AppColors.surface2,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
              color: value ? AppColors.accent : AppColors.lineStrong),
        ),
        child: AnimatedAlign(
          duration: const Duration(milliseconds: 150),
          alignment: value ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            width: 14,
            height: 14,
            decoration: BoxDecoration(
              color: value ? AppColors.accentInk : AppColors.textMuted,
              shape: BoxShape.circle,
            ),
          ),
        ),
      ),
    );
  }
}

/// `.cd-box` — square checkbox used on the Clear-data screen.
class SquareCheck extends StatelessWidget {
  final bool value;
  const SquareCheck(this.value, {super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 18,
      height: 18,
      decoration: BoxDecoration(
        color: value ? AppColors.accent : Colors.transparent,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
            color: value ? AppColors.accent : AppColors.lineStrong, width: 1.5),
      ),
      child: value
          ? Icon(Icons.check, size: 12, color: AppColors.accentInk)
          : null,
    );
  }
}

/// `.setting` — label, optional mono value, chevron. Used across Settings.
class SettingRow extends StatelessWidget {
  final String label;
  final String? value;
  final bool valueOn;
  final Widget? trailing;
  final VoidCallback? onTap;

  const SettingRow({
    super.key,
    required this.label,
    this.value,
    this.valueOn = false,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        child: Row(
          children: [
            Expanded(child: Text(label, style: AppTheme.sans(size: 13))),
            if (value != null)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text(
                  value!,
                  style: AppTheme.mono(
                    size: 11.5,
                    color: valueOn ? AppColors.text : AppColors.textFaint,
                  ),
                ),
              ),
            if (trailing != null)
              trailing!
            else if (onTap != null)
              Icon(Icons.chevron_right, size: 16, color: AppColors.textMuted),
          ],
        ),
      ),
    );
  }
}

/// `.row-item` / `.task-line` — icon tile, title + mono subtitle, trailing slot.
class ListRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final Widget? below;
  final VoidCallback? onTap;

  const ListRow({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.below,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: AppColors.surface2,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, size: 15, color: AppColors.textDim),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: AppTheme.sans(size: 12.5, w: FontWeight.w600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (subtitle != null && subtitle!.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Text(
                        subtitle!,
                        style: AppTheme.mono(
                            size: 10.5, color: AppColors.textMuted),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  if (below != null)
                    Padding(
                        padding: const EdgeInsets.only(top: 5), child: below!),
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 10), trailing!],
          ],
        ),
      ),
    );
  }
}

/// Centred muted line used for every empty state ("No tasks yet…").
class EmptyNote extends StatelessWidget {
  final String text;
  const EmptyNote(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Center(
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: AppTheme.sans(size: 12, color: AppColors.textMuted),
        ),
      ),
    );
  }
}

/// A settings row for a feature that is intentionally not built yet.
///
/// Reads as a real entry so the product is honest about its scope, but is
/// visually muted and non-interactive: no chevron, no tap handler, just the
/// label, a short "Coming soon" marker, and (optionally) a one-line hint about
/// when it will arrive. Nothing here pretends to be usable.
class ComingSoonRow extends StatelessWidget {
  final String label;
  final String? detail;
  final IconData? icon;

  const ComingSoonRow({
    super.key,
    required this.label,
    this.detail,
    this.icon,
  });


  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      child: Row(
        children: [
          if (icon != null) ...[
            Icon(icon, size: 16, color: AppColors.textFaint),
            const SizedBox(width: 9),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: AppTheme.sans(
                    size: 13,
                    color: AppColors.textFaint,
                  ),
                ),
                if (detail != null && detail!.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    detail!,
                    style: AppTheme.sans(
                      size: 11,
                      color: AppColors.textMuted,
                      height: 1.4,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: AppColors.surface2,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: AppColors.lineStrong),
            ),
            child: Text(
              'Coming soon',
              style: AppTheme.sans(
                size: 9.5,
                color: AppColors.textFaint,
                w: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Joins rows with hairline dividers, the way every card in the wireframe does.
List<Widget> withDividers(List<Widget> rows) {
  final out = <Widget>[];
  for (var i = 0; i < rows.length; i++) {
    out.add(rows[i]);
    if (i != rows.length - 1) out.add(const RowDivider());
  }
  return out;
}

/// A screen's own Material backing.
///
/// Every screen here is used two ways: as a page inside the shell's
/// [Scaffold], and pushed on its own as a route. Only the first supplies a
/// [Material] ancestor — and without one, `MaterialApp` styles every `Text`
/// with its debug fallback, which is where the yellow-green double underlines
/// all over pushed Settings came from. Giving each screen its own surface
/// makes it correct in both positions and costs nothing in the shell, where
/// this simply paints the same background again.
class ScreenSurface extends StatelessWidget {
  final Widget child;

  const ScreenSurface({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      // The app draws its own typography everywhere; this is a surface, not a
      // theme, so nothing here should tint or restyle what it wraps.
      type: MaterialType.canvas,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
        decoration: AppTheme.backdrop,
        child: child,
      ),
    );
  }
}
