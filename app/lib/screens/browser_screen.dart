import 'package:flutter/material.dart';

import '../browser/browser_tab.dart';
import '../browser/tab_manager.dart';
import '../router/intent_router.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/menu_sheet.dart';
import '../widgets/bottom_nav.dart';
import '../widgets/toast.dart';

/// Drill-in screens the browser's ⋮ menu can ask the shell to open.
enum BrowserDestination { privacy, settings, downloads }

/// The visible browser (S2 · browser state): address bar with the lock, the
/// rendered page at full height, and the Back / Forward / (+) / Tabs / Menu
/// bar floating over it. Tabs are owned by the shared [TabManager], so
/// switching preserves each tab's engine state.
///
/// The page fills the whole area under the address bar and the chrome floats
/// on top of it, collapsing as the user scrolls down — the page is never
/// squeezed into a shorter viewport by the bar.
class BrowserScreen extends StatefulWidget {
  final TabManager tabs;

  /// Jump to the Tabs destination in the shell.
  final VoidCallback onShowTabs;

  /// Open a drill-in screen from the ⋮ menu.
  final ValueChanged<BrowserDestination> onOpenDestination;

  const BrowserScreen({
    super.key,
    required this.tabs,
    required this.onShowTabs,
    required this.onOpenDestination,
  });

  @override
  State<BrowserScreen> createState() => _BrowserScreenState();
}

class _BrowserScreenState extends State<BrowserScreen> {
  final _address = TextEditingController();
  final _addressFocus = FocusNode();

  BrowserTab? get _tab => widget.tabs.active;

  BrowserTab? _noticeTab;

  @override
  void initState() {
    super.initState();
    _address.text = _tab?.url ?? '';
    _listenForDownloads(_tab);
    _addressFocus.addListener(() {
      // Editing the address should never happen behind a hidden bar.
      if (_addressFocus.hasFocus) _tab?.showChrome();
    });
  }

  @override
  void dispose() {
    _noticeTab?.downloadNotice.removeListener(_onDownloadNotice);
    _address.dispose();
    _addressFocus.dispose();
    super.dispose();
  }

  /// Downloads are handed to Android's DownloadManager by the engine; the user
  /// still needs to be told one started (or that it could not).
  void _listenForDownloads(BrowserTab? tab) {
    if (identical(tab, _noticeTab)) return;
    _noticeTab?.downloadNotice.removeListener(_onDownloadNotice);
    _noticeTab = tab;
    tab?.downloadNotice.addListener(_onDownloadNotice);
  }

  void _onDownloadNotice() {
    final notice = _noticeTab?.downloadNotice.value;
    if (notice == null || !mounted) return;
    AppToast.show(context, notice);
    _noticeTab?.downloadNotice.value = null;
  }

  /// Keep the field in sync with the tab, but never fight the user's typing.
  void _syncAddress(BrowserTab tab) {
    if (_addressFocus.hasFocus) return;
    if (_address.text == tab.url) return;
    _address.value = TextEditingValue(text: tab.url);
  }

  void _navigate(String input) {
    final tab = _tab;
    if (tab == null || input.trim().isEmpty) return;
    _addressFocus.unfocus();
    // From the address bar even an instruction-shaped line is browsed, not
    // dispatched to the agent: the agent is driven from Home, so the address
    // bar never surprises the user with a background task.
    tab.load(IntentRouter.toUrl(input));
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.tabs,
      builder: (context, _) {
        final tab = widget.tabs.active;
        if (tab == null) return const Scaffold(backgroundColor: AppColors.bg);
        _listenForDownloads(tab);

        return AnimatedBuilder(
          animation: tab,
          builder: (context, __) {
            _syncAddress(tab);
            return Scaffold(
              backgroundColor: AppColors.bg,
              body: ValueListenableBuilder<bool>(
                valueListenable: tab.chromeVisible,
                builder: (context, chromeVisible, ___) => Column(
                  children: [
                    SafeArea(bottom: false, child: _addressBar(tab)),
                    Expanded(
                      child: Stack(
                        children: [
                          // The page owns the full height; the bar floats over it.
                          Positioned.fill(child: _pageSurface(tab)),
                          if (tab.isLoading)
                            const Align(
                              alignment: Alignment.topCenter,
                              child: LinearProgressIndicator(
                                minHeight: 2,
                                color: AppColors.accent,
                                backgroundColor: AppColors.surface2,
                              ),
                            ),
                          if (tab.error != null && !tab.isLoading)
                            Positioned.fill(child: _errorView(tab)),
                          DebugOverlay(
                            bottomInset: chromeVisible ? BrowserNav.height(context) + 12 : 16,
                          ),
                          Positioned(
                            left: 0,
                            right: 0,
                            bottom: 0,
                            child: BrowserNav(
                              visible: chromeVisible,
                              canGoBack: tab.canGoBack,
                              canGoForward: tab.canGoForward,
                              onBack: tab.goBack,
                              onForward: tab.goForward,
                              onNewTab: () {
                                widget.tabs.newTab();
                                AppToast.show(context, 'New tab');
                              },
                              onTabs: widget.onShowTabs,
                              onMenu: _openMenu,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  /// The page itself — our own WebView, hosted as a platform view. The engine
  /// returns a neutral placeholder where no WebView exists (widget tests, the
  /// design preview) rather than taking the screen down.
  Widget _pageSurface(BrowserTab tab) => tab.engine.buildView();

  Widget _addressBar(BrowserTab tab) {
    return Container(
      height: 42,
      margin: const EdgeInsets.fromLTRB(12, 4, 12, 6),
      padding: const EdgeInsets.only(left: 12, right: 4),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          GestureDetector(
            onTap: () => widget.onOpenDestination(BrowserDestination.privacy),
            behavior: HitTestBehavior.opaque,
            child: Row(
              children: [
                Icon(
                  tab.isSecure ? Icons.lock_outline : Icons.lock_open,
                  size: 14,
                  color: tab.isSecure ? AppColors.text : AppColors.textFaint,
                ),
                if (tab.blocked.total > 0) ...[
                  const SizedBox(width: 5),
                  Text(
                    '${tab.blocked.total}',
                    style: AppTheme.mono(size: 10, w: FontWeight.w600, color: AppColors.textFaint),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _address,
              focusNode: _addressFocus,
              style: AppTheme.mono(size: 12.5, color: AppColors.textDim),
              cursorColor: AppColors.accent,
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: 'Search or enter address',
                hintStyle: AppTheme.mono(size: 12.5, color: AppColors.textFaint),
              ),
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              onSubmitted: _navigate,
            ),
          ),
          GestureDetector(
            onTap: tab.reload,
            behavior: HitTestBehavior.opaque,
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 6),
              child: Icon(Icons.refresh, size: 15, color: AppColors.textFaint),
            ),
          ),
          GestureDetector(
            onTap: _openMenu,
            behavior: HitTestBehavior.opaque,
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8),
              child: Icon(Icons.more_vert, size: 16, color: AppColors.textDim),
            ),
          ),
        ],
      ),
    );
  }

  Widget _errorView(BrowserTab tab) {
    return Container(
      color: AppColors.bg,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.wifi_off, size: 30, color: AppColors.textFaint),
          const SizedBox(height: 12),
          Text("Couldn't load this page", style: AppTheme.sans(size: 14, w: FontWeight.w600)),
          const SizedBox(height: 6),
          Text(
            tab.error ?? 'Network error',
            textAlign: TextAlign.center,
            style: AppTheme.sans(size: 12, color: AppColors.textFaint),
          ),
          const SizedBox(height: 18),
          SizedBox(width: 180, child: ActionButton('Retry', solid: true, onTap: tab.reload)),
        ],
      ),
    );
  }

  void _openMenu() {
    final tab = _tab;
    if (tab == null) return;
    tab.showChrome();
    showMenuSheet(context, [
      SheetItem(Icons.visibility_off_outlined, 'New private tab', () {
        widget.tabs.newTab(isPrivate: true);
        AppToast.show(context, 'Private tab opened');
      }),
      SheetItem(Icons.bookmark_outline, 'Bookmark this page', () async {
        if (tab.url.isEmpty) {
          AppToast.show(context, 'Nothing to bookmark');
          return;
        }
        await NativeBridge.guard(
          () => NativeBridge.addBookmark(tab.url, tab.label),
          null,
          'bookmark failed',
        );
        if (mounted) AppToast.show(context, 'Bookmarked');
      }),
      SheetItem(Icons.bookmarks_outlined, 'Bookmarks', () async {
        final marks = await NativeBridge.guard(
          NativeBridge.bookmarks,
          const <Map<String, dynamic>>[],
          'bookmarks unavailable',
        );
        if (!mounted) return;
        if (marks.isEmpty) {
          AppToast.show(context, 'No bookmarks');
          return;
        }
        _showBookmarks(marks);
      }),
      SheetItem(Icons.shield_outlined, 'Privacy report',
          () => widget.onOpenDestination(BrowserDestination.privacy)),
      SheetItem(Icons.settings_rounded, 'Settings',
          () => widget.onOpenDestination(BrowserDestination.settings)),
      SheetItem(Icons.download_rounded, 'Downloads',
          () => widget.onOpenDestination(BrowserDestination.downloads)),
      SheetItem(Icons.close, 'Close all tabs', () {
        widget.tabs.closeAll();
        AppToast.show(context, 'All tabs closed');
        widget.onShowTabs();
      }),
    ]);
  }

  void _showBookmarks(List<Map<String, dynamic>> marks) {
    showMenuSheet(context, [
      for (final m in marks.take(8))
        SheetItem(Icons.link, m['title'] as String? ?? m['url'] as String? ?? '', () {
          final url = m['url'] as String? ?? '';
          if (url.isNotEmpty) _tab?.load(url);
        }),
    ]);
  }
}
