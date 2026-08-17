import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class SheetItem {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const SheetItem(this.icon, this.label, this.onTap);
}

/// `.menu-sheet` — the browser's ⋮ menu: a rounded panel that slides up from
/// the bottom with hairline-separated rows.
Future<void> showMenuSheet(BuildContext context, List<SheetItem> items) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x80000000),
    builder: (sheetContext) => Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        border: Border(top: BorderSide(color: AppColors.lineStrong)),
      ),
      padding: EdgeInsets.only(
        top: 6,
        left: 12,
        right: 12,
        bottom: MediaQuery.of(sheetContext).padding.bottom + 18,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < items.length; i++)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                Navigator.of(sheetContext).pop();
                items[i].onTap();
              },
              child: Container(
                decoration: BoxDecoration(
                  border: i == items.length - 1
                      ? null
                      : const Border(bottom: BorderSide(color: AppColors.line)),
                ),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                child: Row(
                  children: [
                    SizedBox(width: 18, child: Icon(items[i].icon, size: 15, color: AppColors.textFaint)),
                    const SizedBox(width: 12),
                    Expanded(child: Text(items[i].label, style: AppTheme.sans(size: 13.5))),
                    const Icon(Icons.chevron_right, size: 14, color: AppColors.textMuted),
                  ],
                ),
              ),
            ),
        ],
      ),
    ),
  );
}
