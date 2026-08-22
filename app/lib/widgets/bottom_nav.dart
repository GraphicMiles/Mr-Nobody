import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Height of the bar's content row (icons + labels).
const double _barContent = 46;

/// The "new" button reads as one of the row's items — same row, same optical
/// centre — instead of a raised circle hanging above the bar. A raised button
/// forces the bar to reserve empty space above itself, and that reserved strip
/// covers the top of whatever is underneath (a rendered page, most visibly).
const double _plusSize = 36;

/// Stable handle for the "new" button (used by tests and by anything that
/// needs to point at it).
const Key kNavNewButtonKey = Key('nav-new-button');
const Key kBottomNavSurfaceKey = Key('bottom-nav-surface');

class _NavShell extends StatelessWidget {
  /// Two items, the "new" button, then two items.
  final List<Widget> items;
  final VoidCallback onPlus;
  final bool visible;
  final Key? surfaceKey;

  const _NavShell({
    required this.items,
    required this.onPlus,
    required this.visible,
    this.surfaceKey,
  });

  @override
  Widget build(BuildContext context) {
    assert(items.length == 4, 'two items, the + slot, then two items');
    final safeBottom = MediaQuery.of(context).padding.bottom;

    return AnimatedSlide(
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
      offset: visible ? Offset.zero : const Offset(0, 1),
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 240),
        opacity: visible ? 1 : 0,
        child: AnimatedContainer(
          key: surfaceKey,
          duration: const Duration(milliseconds: 240),
          curve: Curves.easeOutCubic,
          decoration: BoxDecoration(
            color: AppColors.bg,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          padding: EdgeInsets.only(
              left: 10, right: 10, top: 6, bottom: safeBottom + 8),
          child: SizedBox(
            height: _barContent,
            // A fixed-height bar must not be re-flowed by a large system font.
            child: MediaQuery.withClampedTextScaling(
              maxScaleFactor: 1.15,
              child: Row(
                children: [
                  items[0],
                  items[1],
                  Expanded(
                    child: Center(
                      child: GestureDetector(
                        key: kNavNewButtonKey,
                        onTap: onPlus,
                        behavior: HitTestBehavior.opaque,
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 220),
                          curve: Curves.easeOutCubic,
                          width: _plusSize,
                          height: _plusSize,
                          decoration: BoxDecoration(
                            color: AppColors.accent,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(Icons.add,
                              color: AppColors.accentInk, size: 19),
                        ),
                      ),
                    ),
                  ),
                  items[2],
                  items[3],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool active;
  final bool enabled;
  final VoidCallback onTap;

  const _NavItem({
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    final target = active
        ? AppColors.accent
        : (enabled ? AppColors.textFaint : AppColors.textMuted);
    return Expanded(
      child: GestureDetector(
        onTap: enabled ? onTap : null,
        behavior: HitTestBehavior.opaque,
        child: TweenAnimationBuilder<Color?>(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          tween: ColorTween(end: target),
          builder: (context, color, _) => Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 18, color: color ?? target),
              const SizedBox(height: 4),
              Text(
                label,
                maxLines: 1,
                softWrap: false,
                overflow: TextOverflow.clip,
                style: AppTheme.mono(
                  size: 9,
                  w: FontWeight.w600,
                  color: color ?? target,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Bottom navigation — Home · Tabs · (+) · Tasks · Settings. Hides on
/// scroll-down and returns on scroll-up.
class BottomNav extends StatelessWidget {
  final int selected;
  final ValueChanged<int> onSelect;
  final VoidCallback onNew;
  final bool visible;

  /// Height the bar occupies, so overlaying screens can pad their content.
  static double height(BuildContext context) =>
      _barContent + 14 + MediaQuery.of(context).padding.bottom;

  const BottomNav({
    super.key,
    required this.selected,
    required this.onSelect,
    required this.onNew,
    this.visible = true,
  });

  @override
  Widget build(BuildContext context) {
    return _NavShell(
      visible: visible,
      surfaceKey: kBottomNavSurfaceKey,
      onPlus: onNew,
      items: [
        _NavItem(
            icon: Icons.home_rounded,
            label: 'Home',
            active: selected == 0,
            onTap: () => onSelect(0)),
        _NavItem(
            icon: Icons.layers_rounded,
            label: 'Tabs',
            active: selected == 1,
            onTap: () => onSelect(1)),
        _NavItem(
            icon: Icons.checklist_rounded,
            label: 'Tasks',
            active: selected == 2,
            onTap: () => onSelect(2)),
        _NavItem(
            icon: Icons.settings_rounded,
            label: 'Settings',
            active: selected == 3,
            onTap: () => onSelect(3)),
      ],
    );
  }
}

/// The browser's own bar: Back · Forward · (+) · Tabs · Menu.
class BrowserNav extends StatelessWidget {
  final VoidCallback onBack;
  final VoidCallback onForward;
  final VoidCallback onNewTab;
  final VoidCallback onTabs;
  final VoidCallback onMenu;
  final bool canGoBack;
  final bool canGoForward;
  final bool visible;

  static double height(BuildContext context) => BottomNav.height(context);

  const BrowserNav({
    super.key,
    required this.onBack,
    required this.onForward,
    required this.onNewTab,
    required this.onTabs,
    required this.onMenu,
    this.canGoBack = true,
    this.canGoForward = true,
    this.visible = true,
  });

  @override
  Widget build(BuildContext context) {
    return _NavShell(
      visible: visible,
      onPlus: onNewTab,
      items: [
        _NavItem(
            icon: Icons.arrow_back,
            label: 'Back',
            enabled: canGoBack,
            onTap: onBack),
        _NavItem(
            icon: Icons.arrow_forward,
            label: 'Forward',
            enabled: canGoForward,
            onTap: onForward),
        _NavItem(icon: Icons.layers_rounded, label: 'Tabs', onTap: onTabs),
        _NavItem(icon: Icons.more_horiz, label: 'Menu', onTap: onMenu),
      ],
    );
  }
}
