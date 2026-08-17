import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../browser/tab_manager.dart';

/// Sessions / Tabs (S3) — the real tab list from the shared [TabManager]:
/// 2-column card grid, PRIVATE badge, close, and a dashed "+" card.
class TabsScreen extends StatelessWidget {
  final TabManager tabs;
  final VoidCallback onOpenTab;
  const TabsScreen({super.key, required this.tabs, required this.onOpenTab});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: tabs,
      builder: (context, _) => Column(
        children: [
          _header(context),
          Expanded(
            child: tabs.length == 0
                ? const Center(child: Text('No tabs — tap + to open one', style: TextStyle(color: AppColors.textFaint)))
                : GridView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 20),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 2,
                      mainAxisSpacing: 10,
                      crossAxisSpacing: 10,
                      childAspectRatio: 0.82,
                    ),
                    itemCount: tabs.length + 1,
                    itemBuilder: (c, i) {
                      if (i == tabs.length) {
                        return _NewTabCard(onTap: () { tabs.newTab(); onOpenTab(); });
                      }
                      final tab = tabs.tabs[i];
                      final active = i == tabs.activeIndex;
                      return _TabCard(
                        title: tab.label,
                        isPrivate: tab.isPrivate,
                        active: active,
                        onTap: () { tabs.select(i); onOpenTab(); },
                        onClose: () => tabs.close(i),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  Widget _header(BuildContext context) {
    return Container(
      color: AppColors.surface,
      padding: EdgeInsets.only(left: 8, top: 8 + MediaQuery.of(context).padding.top, right: 12, bottom: 8),
      child: Row(
        children: [
          IconButton(onPressed: () {}, icon: const Icon(Icons.chevron_left, color: AppColors.textDim, size: 26)),
          Text('Sessions', style: AppTheme.sans(size: 16, w: FontWeight.w700)),
          const Spacer(),
          Text('${tabs.length}', style: AppTheme.sans(size: 13, w: FontWeight.w700, color: AppColors.textDim)),
        ],
      ),
    );
  }
}

class _TabCard extends StatelessWidget {
  final String title;
  final bool isPrivate;
  final bool active;
  final VoidCallback onTap;
  final VoidCallback onClose;
  const _TabCard({required this.title, required this.isPrivate, required this.active, required this.onTap, required this.onClose});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: active ? AppColors.lineStrong : AppColors.line, width: active ? 2 : 1),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 9, 6, 7),
              child: Row(
                children: [
                  Container(width: 14, height: 14, decoration: BoxDecoration(color: AppColors.surface3, borderRadius: BorderRadius.circular(4))),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(title, style: AppTheme.sans(size: 12, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                  ),
                  GestureDetector(
                    onTap: onClose,
                    child: const Padding(
                      padding: EdgeInsets.all(4),
                      child: Icon(Icons.close, size: 13, color: AppColors.textFaint),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Container(
                margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                decoration: BoxDecoration(color: AppColors.surface2, borderRadius: BorderRadius.circular(10)),
                child: Stack(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(9),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          FractionallySizedBox(widthFactor: 0.7, child: Container(height: 4, decoration: BoxDecoration(color: AppColors.surface3, borderRadius: BorderRadius.circular(2)))),
                          const SizedBox(height: 8),
                          FractionallySizedBox(widthFactor: 0.5, child: Container(height: 4, decoration: BoxDecoration(color: AppColors.surface3, borderRadius: BorderRadius.circular(2)))),
                        ],
                      ),
                    ),
                    if (isPrivate)
                      Positioned(
                        top: 6,
                        right: 6,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                          decoration: BoxDecoration(color: AppColors.accent, borderRadius: BorderRadius.circular(999)),
                          child: Text('PRIVATE', style: AppTheme.mono(size: 7.5, color: AppColors.accentInk, w: FontWeight.w700)),
                        ),
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
}

class _NewTabCard extends StatelessWidget {
  final VoidCallback onTap;
  const _NewTabCard({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.line),
        ),
        child: const Icon(Icons.add, size: 26, color: AppColors.textFaint),
      ),
    );
  }
}
