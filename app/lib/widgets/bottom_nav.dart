import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Bottom navigation — Home · Tabs · (+) · Tasks · Settings, with the raised
/// circular "+" hanging above the bar, exactly like `.bottombar` in the
/// wireframe. Hides itself on scroll-down and comes back on scroll-up.
class BottomNav extends StatelessWidget {
  final int selected;
  final ValueChanged<int> onSelect;
  final VoidCallback onNew;
  final bool visible;

  /// Index of the raised "+" slot; the four tabs are 0..3.
  static const int newTabIndex = 4;

  const BottomNav({
    super.key,
    required this.selected,
    required this.onSelect,
    required this.onNew,
    this.visible = true,
  });

  @override
  Widget build(BuildContext context) {
    final safeBottom = MediaQuery.of(context).padding.bottom;
    return AnimatedSlide(
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
      offset: visible ? Offset.zero : const Offset(0, 1.05),
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 260),
        opacity: visible ? 1 : 0,
        child: Container(
          decoration: const BoxDecoration(
            color: AppColors.bg,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          padding: EdgeInsets.only(left: 14, right: 14, top: 8, bottom: safeBottom + 10),
          child: SizedBox(
            height: 46,
            child: Stack(
              clipBehavior: Clip.none,
              alignment: Alignment.topCenter,
              children: [
                Row(
                  children: [
                    _item(Icons.home_rounded, 'Home', 0),
                    _item(Icons.layers_rounded, 'Tabs', 1),
                    const SizedBox(width: 56), // slot reserved for the raised "+"
                    _item(Icons.checklist_rounded, 'Tasks', 2),
                    _item(Icons.settings_rounded, 'Settings', 3),
                  ],
                ),
                Positioned(
                  top: -24,
                  child: GestureDetector(
                    onTap: onNew,
                    child: Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: AppColors.accent,
                        shape: BoxShape.circle,
                        border: Border.all(color: AppColors.bg, width: 4),
                      ),
                      child: const Icon(Icons.add, color: AppColors.accentInk, size: 18),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _item(IconData icon, String label, int index) {
    final on = selected == index;
    return Expanded(
      child: GestureDetector(
        onTap: () => onSelect(index),
        behavior: HitTestBehavior.opaque,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 18, color: on ? AppColors.accent : AppColors.textFaint),
            const SizedBox(height: 4),
            Text(
              label,
              style: AppTheme.mono(
                size: 9,
                w: FontWeight.w600,
                color: on ? AppColors.accent : AppColors.textFaint,
              ),
            ),
          ],
        ),
      ),
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
    final safeBottom = MediaQuery.of(context).padding.bottom;
    return AnimatedSlide(
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
      offset: visible ? Offset.zero : const Offset(0, 1.05),
      child: Container(
        decoration: const BoxDecoration(
          color: AppColors.bg,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        padding: EdgeInsets.only(left: 14, right: 14, top: 8, bottom: safeBottom + 10),
        child: SizedBox(
          height: 46,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.topCenter,
            children: [
              Row(
                children: [
                  _item(Icons.arrow_back, 'Back', onBack, enabled: canGoBack),
                  _item(Icons.arrow_forward, 'Forward', onForward, enabled: canGoForward),
                  const SizedBox(width: 56),
                  _item(Icons.layers_rounded, 'Tabs', onTabs),
                  _item(Icons.more_horiz, 'Menu', onMenu),
                ],
              ),
              Positioned(
                top: -24,
                child: GestureDetector(
                  onTap: onNewTab,
                  child: Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: AppColors.accent,
                      shape: BoxShape.circle,
                      border: Border.all(color: AppColors.bg, width: 4),
                    ),
                    child: const Icon(Icons.add, color: AppColors.accentInk, size: 18),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _item(IconData icon, String label, VoidCallback onTap, {bool enabled = true}) {
    final color = enabled ? AppColors.textFaint : AppColors.textMuted;
    return Expanded(
      child: GestureDetector(
        onTap: enabled ? onTap : null,
        behavior: HitTestBehavior.opaque,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(height: 4),
            Text(label, style: AppTheme.mono(size: 9, w: FontWeight.w600, color: color)),
          ],
        ),
      ),
    );
  }
}
