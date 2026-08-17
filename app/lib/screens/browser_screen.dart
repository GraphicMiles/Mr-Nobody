import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

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
/// rendered page, and the Back / Forward / + / Tabs / Menu bar. Tabs are owned
/// by the shared [TabManager], so switching preserves each tab's engine state.
///
/// This is the human path. The agent's headless path never renders here.
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
  bool _loading = false;
  bool _canBack = false;
  bool _canForward = false;
  String? _error;
  int? _boundTabId;

  BrowserTab? get _tab => widget.tabs.active;

  @override
  void initState() {
    super.initState();
    widget.tabs.addListener(_onTabsChanged);
    _bindTab();
  }

  @override
  void dispose() {
    widget.tabs.removeListener(_onTabsChanged);
    _address.dispose();
    _addressFocus.dispose();
    super.dispose();
  }

  void _onTabsChanged() {
    _bindTab();
    if (mounted) setState(() {});
  }

  /// Attach to the active tab's engine callbacks (once per tab).
  void _bindTab() {
    final tab = widget.tabs.active;
    if (tab == null || tab.id == _boundTabId) return;
    _boundTabId = tab.id;
    tab.engine
      ..onLoadingChanged = (l) {
        if (!mounted) return;
        setState(() => _loading = l);
        if (!l) _refreshHistoryButtons();
      }
      ..onUrlChanged = (u) {
        if (!mounted) return;
        setState(() {
          if (!_addressFocus.hasFocus) _address.text = u;
          _error = null;
        });
      }
      ..onTitleChanged = (_) {
        if (mounted) setState(() {});
      }
      ..onError = (e) {
        if (mounted) setState(() => _error = e);
      };
    _address.text = tab.url;
    _refreshHistoryButtons();
  }

  Future<void> _refreshHistoryButtons() async {
    final tab = _tab;
    if (tab == null || !tab.engine.isAvailable) return;
    final back = await tab.engine.canGoBack();
    final forward = await tab.engine.canGoForward();
    if (!mounted) return;
    setState(() {
      _canBack = back;
      _canForward = forward;
    });
  }

  void _navigate(String input) {
    final tab = _tab;
    if (tab == null || input.trim().isEmpty) return;
    setState(() => _error = null);
    _addressFocus.unfocus();
    // From the address bar even an instruction-shaped line is browsed, not
    // dispatched to the agent: the agent is driven from Home, so the address
    // bar never surprises the user with a background task.
    tab.engine.loadUrl(IntentRouter.toUrl(input));
  }

  @override
  Widget build(BuildContext context) {
    final tab = _tab;
    if (tab == null) return const Scaffold(backgroundColor: AppColors.bg);

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: Column(
        children: [
          SafeArea(bottom: false, child: _addressBar(tab)),
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(child: _pageSurface(tab)),
                if (_loading)
                  const Align(
                    alignment: Alignment.topCenter,
                    child: LinearProgressIndicator(
                      minHeight: 2,
                      color: AppColors.accent,
                      backgroundColor: AppColors.surface2,
                    ),
                  ),
                if (_error != null && !_loading) Positioned.fill(child: _errorView(tab)),
                const Positioned.fill(child: DebugOverlay(bottomInset: 16)),
              ],
            ),
          ),
          BrowserNav(
            canGoBack: _canBack,
            canGoForward: _canForward,
            onBack: () => tab.engine.goBack(),
            onForward: () => tab.engine.goForward(),
            onNewTab: () {
              widget.tabs.newTab();
              AppToast.show(context, 'New tab');
            },
            onTabs: widget.onShowTabs,
            onMenu: _openMenu,
          ),
        ],
      ),
    );
  }

  /// The page itself. On Android this is the platform WebView; where no WebView
  /// platform exists (design preview, widget tests) we show a neutral
  /// placeholder rather than taking the screen down.
  Widget _pageSurface(BrowserTab tab) {
    if (!tab.engine.isAvailable) {
      return Container(
        color: AppColors.bg,
        alignment: Alignment.center,
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Text(
            tab.url.isEmpty
                ? 'New tab'
                : 'Page rendering uses the Android System WebView.\n\n${tab.url}',
            textAlign: TextAlign.center,
            style: AppTheme.mono(size: 11.5, color: AppColors.textMuted, height: 1.6),
          ),
        ),
      );
    }
    return WebViewWidget(controller: tab.engine.controller);
  }

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
          Icon(
            tab.isSecure ? Icons.lock_outline : Icons.lock_open,
            size: 14,
            color: tab.isSecure ? AppColors.text : AppColors.textFaint,
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
            onTap: () => tab.engine.reload(),
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
            _error ?? 'Network error',
            textAlign: TextAlign.center,
            style: AppTheme.sans(size: 12, color: AppColors.textFaint),
          ),
          const SizedBox(height: 18),
          SizedBox(
            width: 180,
            child: ActionButton(
              'Retry',
              solid: true,
              onTap: () {
                setState(() => _error = null);
                tab.engine.reload();
              },
            ),
          ),
        ],
      ),
    );
  }

  void _openMenu() {
    final tab = _tab;
    if (tab == null) return;
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
          if (url.isNotEmpty) _tab?.engine.loadUrl(url);
        }),
    ]);
  }
}

