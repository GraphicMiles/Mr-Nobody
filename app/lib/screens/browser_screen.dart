import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../browser/browser_tab.dart';
import '../browser/tab_manager.dart';
import '../router/intent_router.dart';
import '../bridge/native_bridge.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/menu_sheet.dart';
import '../widgets/bottom_nav.dart';
import '../widgets/toast.dart';
import 'devtools_screen.dart' hide ActionButton;

/// Drill-in screens the browser's ⋮ menu can ask the shell to open.
enum BrowserDestination { privacy, settings, downloads }

/// The visible browser (S2 · browser state): address bar with the lock, the
/// rendered page at full height, and the Back / Forward / (+) / Tabs / Menu
/// bar floating over it. Tabs are owned by the shared [TabManager], so
/// switching preserves each tab's engine state.
///
/// The page fills the area under the address bar. Browser controls occupy a
/// deterministic sibling region rather than floating over the Android
/// platform view; they collapse as the user scrolls down.
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
  BrowserTab? _approvalTab;
  bool _approvalShowing = false;

  @override
  void initState() {
    super.initState();
    _address.text = _tab?.url ?? '';
    _listenForTabActions(_tab);
    _addressFocus.addListener(() {
      // Editing the address should never happen behind a hidden bar.
      if (_addressFocus.hasFocus) _tab?.showChrome();
      if (mounted) setState(() {}); // show/hide the one-character delete icon
    });
  }

  @override
  void dispose() {
    // Leaving the browser is exactly when the grid's picture should be current.
    _tab?.captureThumbnail();
    _noticeTab?.notice.removeListener(_onNotice);
    _approvalTab?.downloadApproval.removeListener(_onDownloadApproval);
    _address.dispose();
    _addressFocus.dispose();
    super.dispose();
  }

  /// Listen for transient browser notices and download approval requests on the
  /// active tab. Notices use a toast and never replace the page with an error.
  void _listenForTabActions(BrowserTab? tab) {
    final sameTab = tab != null &&
        (identical(tab, _noticeTab) || (tab.id == _noticeTab?.id && tab.id >= 0)) &&
        (identical(tab, _approvalTab) || (tab.id == _approvalTab?.id && tab.id >= 0));
    if (sameTab) return;
    _noticeTab?.notice.removeListener(_onNotice);
    _approvalTab?.downloadApproval.removeListener(_onDownloadApproval);
    _noticeTab = tab;
    _approvalTab = tab;
    tab?.notice.addListener(_onNotice);
    tab?.downloadApproval.addListener(_onDownloadApproval);
    if (tab?.downloadApproval.value != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _onDownloadApproval());
    }
  }

  void _onNotice() {
    final notice = _noticeTab?.notice.value;
    if (notice == null || !mounted) return;
    AppToast.show(context, notice);
    _noticeTab?.notice.value = null;
  }

  Future<void> _onDownloadApproval() async {
    if (_approvalShowing) return;
    final tab = _approvalTab;
    final request = tab?.downloadApproval.value;
    if (tab == null || request == null) return;
    _approvalShowing = true;

    final host = request.sourceHost;
    final allowed = await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (dialogContext) => AlertDialog(
            backgroundColor:
                AppColors.isWarm ? null : AppColors.surface,
            title: Text(
              'Potentially harmful file',
              style: AppTheme.sans(
                size: AppColors.isWarm ? 17 : 16,
                color: AppColors.overlayInk,
                w: FontWeight.w700,
              ),
            ),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  request.name,
                  style: AppTheme.mono(
                    size: 12.5,
                    color: AppColors.overlayInk,
                    w: AppColors.isWarm ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  request.warning,
                  style: AppTheme.sans(
                    size: 12,
                    color: AppColors.overlayMuted,
                    height: 1.45,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Only continue if you trust the website and expected this file.',
                  style: AppTheme.sans(
                    size: 11,
                    color: AppColors.overlayFaint,
                    height: 1.45,
                  ),
                ),
                if (host.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(
                    'Source: $host',
                    style: AppTheme.mono(
                      size: 10,
                      color: AppColors.overlayFaint,
                    ),
                  ),
                ],
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: Text(
                  'Reject',
                  style: AppTheme.sans(
                    size: 13,
                    color: AppColors.overlayMuted,
                    w: AppColors.isWarm ? FontWeight.w600 : FontWeight.w400,
                  ),
                ),
              ),
              if (AppColors.isWarm)
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.overlayInk,
                    foregroundColor: AppColors.overlay,
                  ),
                  onPressed: () => Navigator.pop(dialogContext, true),
                  child: const Text('Go ahead'),
                )
              else
                TextButton(
                  onPressed: () => Navigator.pop(dialogContext, true),
                  child: Text(
                    'Go ahead',
                    style: AppTheme.sans(
                      size: 13,
                      color: AppColors.accent,
                      w: FontWeight.w700,
                    ),
                  ),
                ),
            ],
          ),
        ) ??
        false;

    if (!mounted) return;
    final resolved = await tab.resolveDownload(request, allowed);
    if (mounted) {
      AppToast.show(context, allowed && resolved ? 'Download allowed' : 'Download rejected');
    }
    _approvalShowing = false;
    if (mounted && tab.downloadApproval.value != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _onDownloadApproval());
    }
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
    tab.load(IntentRouter.toUrl(
        input, searchEngine: AppState.instance.searchEngine));
  }

  Future<void> _goBack(BrowserTab tab) async {
    _addressFocus.unfocus();
    await tab.goBack();
  }

  Future<void> _goForward(BrowserTab tab) async {
    _addressFocus.unfocus();
    await tab.goForward();
  }

  void _clearAddress() {
    _address.clear();
    _addressFocus.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.tabs,
      builder: (context, _) {
        final tab = widget.tabs.active;
        if (tab == null) return Scaffold(backgroundColor: AppColors.bg);
        _listenForTabActions(tab);

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
                      // Do not stack Flutter chrome over an Android platform
                      // view. Hybrid-composed platform views can win the z
                      // order, which made the bottom bar disappear or left a
                      // single clipped icon on some release builds.
                      child: Stack(
                        children: [
                          Positioned.fill(child: _pageSurface(tab)),
                          if (tab.isLoading)
                            Align(
                              alignment: Alignment.topCenter,
                              child: LinearProgressIndicator(
                                minHeight: 2,
                                color: AppColors.accent,
                                backgroundColor: AppColors.surface2,
                              ),
                            ),
                          if (tab.error != null && !tab.isLoading)
                            Positioned.fill(child: _errorView(tab)),
                          const DebugOverlay(bottomInset: 16),
                        ],
                      ),
                    ),
                    // Keep the browser controls outside the platform-view
                    // bounds. This makes their height deterministic and keeps
                    // them tappable on both debug and release APKs.
                    ClipRect(
                      child: AnimatedSize(
                        duration: const Duration(milliseconds: 240),
                        curve: Curves.easeOutCubic,
                        alignment: Alignment.bottomCenter,
                        child: SizedBox(
                          height: chromeVisible ? BrowserNav.height(context) : 0,
                          child: BrowserNav(
                            visible: chromeVisible,
                            canGoBack: tab.canGoBack,
                            canGoForward: tab.canGoForward,
                            onBack: () => _goBack(tab),
                            onForward: () => _goForward(tab),
                            onNewTab: () {
                              tab.captureThumbnail();
                              _addressFocus.unfocus();
                              widget.tabs.newTab();
                              AppToast.show(context, 'New tab');
                            },
                            onTabs: () {
                              tab.captureThumbnail();
                              widget.onShowTabs();
                            },
                            onMenu: _openMenu,
                          ),
                        ),
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
                if (tab.url.startsWith('http://')) ...[
                  const SizedBox(width: 4),
                  Text(
                    'Not secure',
                    style: AppTheme.mono(size: 9, color: AppColors.textFaint),
                  ),
                ],
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
          if (_addressFocus.hasFocus)
            GestureDetector(
              onTap: _clearAddress,
              behavior: HitTestBehavior.opaque,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6),
                child: Icon(Icons.close, key: const ValueKey('address-delete'),
                    size: 15, color: AppColors.textFaint),
              ),
            ),
          GestureDetector(
            key: const ValueKey('refresh-button'),
            onTap: tab.reload,
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 150),
                child: tab.isLoading
                    ? SizedBox(
                        key: const ValueKey('refresh-loading'),
                        width: 15,
                        height: 15,
                        child: CircularProgressIndicator(
                          strokeWidth: 1.6,
                          color: AppColors.textFaint,
                        ),
                      )
                    : Icon(Icons.refresh, key: const ValueKey('refresh-idle'),
                        size: 15, color: AppColors.textFaint),
              ),
            ),
          ),
          GestureDetector(
            onTap: _openMenu,
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
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
          Icon(Icons.wifi_off, size: 30, color: AppColors.textFaint),
          const SizedBox(height: 12),
          Text("Couldn't load this page", style: AppTheme.sans(size: 14, w: FontWeight.w600)),
          const SizedBox(height: 6),
          Text(
            tab.error ?? 'Network error',
            textAlign: TextAlign.center,
            style: AppTheme.sans(size: 12, color: AppColors.textFaint),
          ),
          const SizedBox(height: 8),
          Text(
            tab.url,
            textAlign: TextAlign.center,
            style: AppTheme.mono(size: 10, color: AppColors.textFaint),
          ),
          const SizedBox(height: 18),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              SizedBox(width: 140, child: ActionButton('Retry', solid: true, onTap: tab.reload)),
              const SizedBox(width: 12),
              SizedBox(width: 140, child: ActionButton('Copy URL', onTap: () {
                // ignore: avoid_print
                Clipboard.setData(ClipboardData(text: tab.url));
                AppToast.show(context, 'URL copied');
              })),
            ],
          ),
          const SizedBox(height: 12),
          if (tab.consoleLogs.value.isNotEmpty)
            Text('${tab.consoleLogs.value.length} console logs — open DevTools from menu',
                style: AppTheme.mono(size: 10, color: AppColors.textMuted)),
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
      SheetItem(Icons.code, 'Developer Tools', () {
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => DevToolsScreen(tab: tab),
        ));
      }),
      SheetItem(Icons.bug_report_outlined, 'Inject Eruda (inspect)', () async {
        await tab.injectEruda();
        if (mounted) AppToast.show(context, 'Eruda injected — look for floating button on page');
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
