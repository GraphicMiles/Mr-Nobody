import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class SheetItem {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const SheetItem(this.icon, this.label, this.onTap);
}

/// The browser and download action sheet.
///
/// Sheets invert to warm cream so they read as a temporary decision surface,
/// not another dark page behind the current one. The contrast remains fully
/// local to the app and introduces no asset or font request.
Future<void> showMenuSheet(BuildContext context, List<SheetItem> items) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    barrierColor:
        AppColors.isWarm ? const Color(0x99000000) : const Color(0x80000000),
    builder: (sheetContext) => Container(
      decoration: BoxDecoration(
        color: AppColors.overlay,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppColors.isWarm ? 28 : 20),
        ),
        border: AppColors.isWarm
            ? null
            : Border(top: BorderSide(color: AppColors.lineStrong)),
      ),
      padding: EdgeInsets.only(
        top: AppColors.isWarm ? 8 : 6,
        left: 12,
        right: 12,
        bottom: MediaQuery.of(sheetContext).padding.bottom + 18,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (AppColors.isWarm)
            Container(
              width: 34,
              height: 4,
              margin: const EdgeInsets.only(bottom: 8),
              decoration: BoxDecoration(
                color: AppColors.overlayLine,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
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
                      : Border(
                          bottom: BorderSide(
                            color: AppColors.isWarm
                                ? AppColors.overlayLine
                                : AppColors.line,
                          ),
                        ),
                ),
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                child: Row(
                  children: [
                    SizedBox(
                      width: 18,
                      child: Icon(
                        items[i].icon,
                        size: AppColors.isWarm ? 16 : 15,
                        color: AppColors.isWarm
                            ? AppColors.overlayMuted
                            : AppColors.textFaint,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        items[i].label,
                        style: AppTheme.sans(
                          size: 13.5,
                          color: AppColors.overlayInk,
                          w: AppColors.isWarm
                              ? FontWeight.w600
                              : FontWeight.w400,
                        ),
                      ),
                    ),
                    Icon(
                      Icons.chevron_right,
                      size: 14,
                      color: AppColors.isWarm
                          ? AppColors.overlayFaint
                          : AppColors.textMuted,
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    ),
  );
}
