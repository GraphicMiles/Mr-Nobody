import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Bottom navigation: Home · Tabs · (+) · Tasks · Settings, with a raised
/// circular "+" floating above the center slot. Monochrome, hairline top border.
class BottomNav extends StatelessWidget {
  final int selected;
  final ValueChanged<int> onSelect;
  const BottomNav({super.key, required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).padding.bottom),
      child: SizedBox(
        height: 58,
        child: Stack(
          alignment: Alignment.topCenter,
          children: [
            Row(
              children: [
                _item(Icons.home_rounded, 'Home', 0),
                _item(Icons.layers_rounded, 'Tabs', 1),
                const Spacer(), // reserved for the raised "+"
                _item(Icons.checklist_rounded, 'Tasks', 2),
                _item(Icons.settings_rounded, 'Settings', 3),
              ],
            ),
            // raised circular "+"
            Positioned(
              top: -24,
              child: GestureDetector(
                onTap: () => onSelect(4), // 4 = "new" action
                child: Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: AppColors.accent,
                    shape: BoxShape.circle,
                    border: Border.all(color: AppColors.bg, width: 4),
                  ),
                  child: const Icon(Icons.add, color: AppColors.accentInk, size: 24),
                ),
              ),
            ),
          ],
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
            Icon(icon, size: 22, color: on ? AppColors.accent : AppColors.textDim),
            const SizedBox(height: 3),
            Text(
              label,
              style: AppTheme.mono(
                size: 9,
                color: on ? AppColors.accent : AppColors.textFaint,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
