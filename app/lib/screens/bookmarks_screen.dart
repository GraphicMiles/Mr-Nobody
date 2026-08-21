import 'package:flutter/material.dart';

import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// Bookmarks — the full saved-pages list, reachable from Settings.
///
/// Until this screen existed, bookmarks were only visible from a tab's sheet
/// menu (and capped at eight), so a user outside the browser had no way to
/// see, open, or delete what they had saved.
class BookmarksScreen extends StatefulWidget {
  /// Opens a URL in a new tab; supplied by the shell so this screen never
  /// needs to know how tabs work.
  final void Function(String url)? onOpenUrl;

  const BookmarksScreen({super.key, this.onOpenUrl});

  @override
  State<BookmarksScreen> createState() => _BookmarksScreenState();
}

class _BookmarksScreenState extends State<BookmarksScreen> {
  List<Map<String, dynamic>> _marks = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final marks = await NativeBridge.guard(
      NativeBridge.bookmarks,
      const <Map<String, dynamic>>[],
      'bookmarks unavailable',
    );
    if (!mounted) return;
    setState(() {
      _marks = marks;
      _loading = false;
    });
  }

  Future<void> _remove(Map<String, dynamic> mark) async {
    final id = (mark['id'] as num?)?.toInt();
    if (id == null) return;
    await NativeBridge.guard(
      () => NativeBridge.removeBookmark(id),
      null,
      'could not remove bookmark',
    );
    if (!mounted) return;
    AppToast.show(context, 'Bookmark removed');
    await _load();
  }

  void _open(Map<String, dynamic> mark) {
    final url = mark['url'] as String? ?? '';
    if (url.isEmpty) return;
    final opener = widget.onOpenUrl;
    if (opener == null) return;
    Navigator.of(context).pop();
    opener(url);
  }

  @override
  Widget build(BuildContext context) {
    return ScreenSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SafeArea(
            bottom: false,
            child: TopBar(
              title: 'Bookmarks',
              onBack: () => Navigator.of(context).pop(),
            ),
          ),
          Expanded(
            child: _loading
                ? Center(
                    child: SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 1.5, color: AppColors.accent),
                    ),
                  )
                : _marks.isEmpty
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            'No bookmarks yet.\nSave a page from a tab\'s menu: '
                            '⋮ → Bookmark this page.',
                            textAlign: TextAlign.center,
                            style: AppTheme.sans(
                                size: 13, color: AppColors.textDim, height: 1.6),
                          ),
                        ),
                      )
                    : ListView(
                        padding: const EdgeInsets.only(bottom: 28),
                        children: [
                          AppCard(
                            child: Column(
                              children: withDividers([
                                for (final m in _marks) _row(m),
                              ]),
                            ),
                          ),
                        ],
                      ),
          ),
        ],
      ),
    );
  }

  Widget _row(Map<String, dynamic> mark) {
    final title = (mark['title'] as String? ?? '').trim();
    final url = mark['url'] as String? ?? '';
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _open(mark),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 6, 10),
        child: Row(
          children: [
            Icon(Icons.bookmark_outline, size: 16, color: AppColors.textDim),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title.isEmpty ? url : title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTheme.sans(size: 13.5, w: FontWeight.w600),
                  ),
                  if (title.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      url,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTheme.mono(size: 10, color: AppColors.textFaint),
                    ),
                  ],
                ],
              ),
            ),
            IconButton(
              icon: Icon(Icons.delete_outline,
                  size: 18, color: AppColors.textDim),
              tooltip: 'Remove bookmark',
              onPressed: () => _remove(mark),
            ),
          ],
        ),
      ),
    );
  }
}
