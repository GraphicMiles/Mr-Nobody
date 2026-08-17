import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import '../browser/browser_tab.dart';
import '../browser/tab_manager.dart';
import '../router/intent_router.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// The visible browser (S2 · browser state): address bar, rendered WebView,
/// loading/error states, back/forward, and a kebab menu. Tabs are owned by the
/// shared [TabManager] so switching preserves each tab's engine/state.
class BrowserScreen extends StatefulWidget {
  final TabManager tabs;
  const BrowserScreen({super.key, required this.tabs});

  @override
  State<BrowserScreen> createState() => _BrowserScreenState();
}

class _BrowserScreenState extends State<BrowserScreen> {
  final _address = TextEditingController();
  bool _loading = false;
  String? _error;

  BrowserTab get _tab => widget.tabs.active!;

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
    super.dispose();
  }

  void _onTabsChanged() {
    _bindTab();
    if (mounted) setState(() {});
  }

  /// Attach this screen to the active tab's engine state callbacks.
  void _bindTab() {
    final tab = widget.tabs.active;
    if (tab == null) return;
    tab.engine.onLoadingChanged = (l) { if (mounted) setState(() => _loading = l); };
    tab.engine.onUrlChanged = (u) { if (mounted) setState(() => _address.text = u); };
    tab.engine.onTitleChanged = (_) { if (mounted) setState(() {}); };
    tab.engine.onError = (e) { if (mounted) setState(() => _error = e); };
    _address.text = tab.url;
  }

  void _navigate(String input) {
    if (input.trim().isEmpty) return;
    setState(() => _error = null);
    final type = IntentRouter.route(input);
    switch (type) {
      case IntentType.url:
        _tab.engine.loadUrl(IntentRouter.toUrl(input));
        break;
      case IntentType.search:
        _tab.engine.loadUrl(IntentRouter.toUrl(input)); // rendered results page
        break;
      case IntentType.task:
        // Instructions are handled by the agent core (via the home input);
        // from the address bar, fall back to a rendered search.
        _tab.engine.loadUrl(IntentRouter.toUrl(input));
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    final tab = widget.tabs.active;
    if (tab == null) return const SizedBox.shrink();

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: Column(
        children: [
          _addressBar(context),
          Expanded(
            child: Stack(
              children: [
                // rendered webpage — never raw HTML source
                WebViewWidget(controller: tab.engine.controller),
                if (_loading) const LinearProgressIndicator(color: AppColors.accent, backgroundColor: AppColors.surface2, minHeight: 2),
                if (_error != null && !_loading) _errorView(context),
              ],
            ),
          ),
          _browserBar(context),
        ],
      ),
    );
  }

  Widget _addressBar(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 6),
      padding: const EdgeInsets.only(left: 12, right: 6),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppColors.line),
      ),
      child: SafeArea(
        bottom: false,
        child: Row(
          children: [
            Icon(_isSecure ? Icons.lock_outline : Icons.lock_open, size: 15, color: _isSecure ? AppColors.text : AppColors.textFaint),
            const SizedBox(width: 6),
            Expanded(
              child: TextField(
                controller: _address,
                style: AppTheme.mono(size: 12.5, color: AppColors.textDim),
                decoration: InputDecoration(
                  hintText: 'Search or enter address',
                  hintStyle: AppTheme.mono(size: 12.5, color: AppColors.textFaint),
                  border: InputBorder.none,
                  isDense: true,
                ),
                keyboardType: TextInputType.url,
                textInputAction: TextInputAction.go,
                onSubmitted: _navigate,
              ),
            ),
            IconButton(
              onPressed: _openMenu,
              icon: const Icon(Icons.more_vert, size: 18, color: AppColors.textDim),
            ),
          ],
        ),
      ),
    );
  }

  bool get _isSecure => _tab.url.startsWith('https://');

  Widget _errorView(BuildContext context) {
    return Container(
      color: AppColors.bg,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.wifi_off, size: 32, color: AppColors.textFaint),
          const SizedBox(height: 12),
          Text('Couldn\'t load this page', style: AppTheme.sans(size: 14, w: FontWeight.w600)),
          const SizedBox(height: 4),
          Text(_error ?? 'Network error', textAlign: TextAlign.center, style: AppTheme.sans(size: 12, color: AppColors.textFaint)),
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: ActionButton('Retry', solid: true, onTap: () { setState(() => _error = null); _tab.engine.reload(); }),
          ),
        ],
      ),
    );
  }

  Widget _browserBar(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).padding.bottom),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _navBtn(Icons.arrow_back, 'Back', () => _tab.engine.goBack()),
          _navBtn(Icons.arrow_forward, 'Forward', () => _tab.engine.goForward()),
          _raisedPlus(),
          _navBtn(Icons.layers_rounded, 'Tabs', () => Navigator.of(context).pop()), // back to shell → tabs
          _navBtn(Icons.more_horiz, 'Menu', _openMenu),
        ],
      ),
    );
  }

  Widget _navBtn(IconData icon, String label, VoidCallback onTap) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 20, color: AppColors.textDim),
              const SizedBox(height: 3),
              Text(label, style: AppTheme.mono(size: 9, color: AppColors.textFaint)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _raisedPlus() {
    return GestureDetector(
      onTap: () {
        widget.tabs.newTab();
      },
      child: Container(
        width: 48,
        height: 48,
        margin: const EdgeInsets.only(bottom: 18),
        decoration: BoxDecoration(
          color: AppColors.accent,
          shape: BoxShape.circle,
          border: Border.all(color: AppColors.bg, width: 4),
        ),
        child: const Icon(Icons.add, size: 24, color: AppColors.accentInk),
      ),
    );
  }

  void _openMenu() {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _menuItem(Icons.visibility_off, 'New private tab', () { widget.tabs.newTab(isPrivate: true); Navigator.pop(c); }),
            _menuItem(Icons.bookmark_outline, 'Bookmark this page', () { _bookmark(); Navigator.pop(c); }),
            _menuItem(Icons.shield_outlined, 'Privacy report', () { Navigator.pop(c); }),
            _menuItem(Icons.close, 'Close all tabs', () { widget.tabs.closeAll(); widget.tabs.newTab(); Navigator.pop(c); }),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Widget _menuItem(IconData icon, String label, VoidCallback onTap) {
    return ListTile(
      leading: Icon(icon, size: 20, color: AppColors.textDim),
      title: Text(label, style: AppTheme.sans(size: 14)),
      onTap: onTap,
    );
  }

  void _bookmark() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Bookmarked "${_tab.label}"'), duration: const Duration(seconds: 1)),
    );
  }
}
