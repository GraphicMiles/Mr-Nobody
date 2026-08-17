import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Geometry shared by both bottom bars.
///
/// The raised "+" must never be positioned outside its parent: a child painted
/// beyond the parent's bounds is clipped away by the enclosing Stack AND is
/// dead to hit-testing, which is exactly how the button ended up sliced in
/// half with an untappable top edge. So the bar reserves [_overhang] of its own
/// height above the bar surface and lays the button out inside that space.
const double _overhang = 22;
const double _barContent = 46;
const double _plusSize = 44;

class _NavShell extends StatelessWidget {
  final List<Widget> items; // exactly four, two per side
  final VoidCallback onPlus;
  final bool visible;

  const _NavShell({required this.items, required this.onPlus, required this.visible});

  @override
  Widget build(BuildContext context) {
    assert(items.length == 4, 'the bar is two items, the raised +, then two items');
    final safeBottom = MediaQuery.of(context).padding.bottom;
    final barHeight = _barContent + 18 + safeBottom; // 8 top + 10 bottom padding

    return AnimatedSlide(
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
      offset: visible ? Offset.zero : const Offset(0, 1.05),
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 260),
        opacity: visible ? 1 : 0,
        child: SizedBox(
          height: _overhang + barHeight,
          child: Stack(
            children: [
              // The bar surface, pinned to the bottom of the reserved box.
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: barHeight,
                child: Container(
                  decoration: const BoxDecoration(
                    color: AppColors.bg,
                    border: Border(top: BorderSide(color: AppColors.line)),
                  ),
                  padding: EdgeInsets.only(left: 14, right: 14, top: 8, bottom: safeBottom + 10),
                  // The bar is a fixed height, so an extreme system font scale
                  // must not be allowed to push the labels out of it.
                  child: MediaQuery.withClampedTextScaling(
                    maxScaleFactor: 1.15,
                    child: Row(
                      children: [
                        items[0],
                        items[1],
                        // Slot the raised button sits in — keeps the four
                        // labels evenly spaced around it.
                        const SizedBox(width: _plusSize + 12),
                        items[2],
                        items[3],
                      ],
                    ),
                  ),
                ),
              ),
              // The raised button, fully inside the box → drawn and tappable.
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                height: _plusSize,
                child: Center(
                  child: GestureDetector(
                    onTap: onPlus,
                    behavior: HitTestBehavior.opaque,
                    child: Container(
                      width: _plusSize,
                      height: _plusSize,
                      decoration: BoxDecoration(
                        color: AppColors.accent,
                        shape: BoxShape.circle,
                        border: Border.all(color: AppColors.bg, width: 4),
                      ),
                      child: const Icon(Icons.add, color: AppColors.accentInk, size: 18),
                    ),
                  ),
                ),
              ),
            ],
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
    final color = active
        ? AppColors.accent
        : (enabled ? AppColors.textFaint : AppColors.textMuted);
    return Expanded(
      child: GestureDetector(
        onTap: enabled ? onTap : null,
        behavior: HitTestBehavior.opaque,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(height: 4),
            Text(
              label,
              maxLines: 1,
              softWrap: false,
              overflow: TextOverflow.clip,
              style: AppTheme.mono(size: 9, w: FontWeight.w600, color: color),
            ),
          ],
        ),
      ),
    );
  }
}

/// Bottom navigation — Home · Tabs · (+) · Tasks · Settings, with the raised
/// circular "+" above the bar, as in `.bottombar` in the wireframe. Hides on
/// scroll-down and returns on scroll-up.
class BottomNav extends StatelessWidget {
  final int selected;
  final ValueChanged<int> onSelect;
  final VoidCallback onNew;
  final bool visible;

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
      onPlus: onNew,
      items: [
        _NavItem(
          icon: Icons.home_rounded,
          label: 'Home',
          active: selected == 0,
          onTap: () => onSelect(0),
        ),
        _NavItem(
          icon: Icons.layers_rounded,
          label: 'Tabs',
          active: selected == 1,
          onTap: () => onSelect(1),
        ),
        _NavItem(
          icon: Icons.checklist_rounded,
          label: 'Tasks',
          active: selected == 2,
          onTap: () => onSelect(2),
        ),
        _NavItem(
          icon: Icons.settings_rounded,
          label: 'Settings',
          active: selected == 3,
          onTap: () => onSelect(3),
        ),
      ],
    );
  }
}

/// The browser's own bottom bar: Back · Forward · (+) · Tabs · Menu.
class BrowserNav extends StatelessWidget {
  final VoidCallback onBack;
  final VoidCallback onForward;
  final VoidCallback onNewTab;
  final VoidCallback onTabs;
  final VoidCallback onMenu;
  final bool canGoBack;
  final bool canGoForward;
  final bool visible;

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
        _NavItem(icon: Icons.arrow_back, label: 'Back', enabled: canGoBack, onTap: onBack),
        _NavItem(icon: Icons.arrow_forward, label: 'Forward', enabled: canGoForward, onTap: onForward),
        _NavItem(icon: Icons.layers_rounded, label: 'Tabs', onTap: onTabs),
        _NavItem(icon: Icons.more_horiz, label: 'Menu', onTap: onMenu),
      ],
    );
  }
}
