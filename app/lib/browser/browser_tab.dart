import 'package:flutter/foundation.dart';

import 'webview_browser_engine.dart';

/// One browser tab — stable identity, its own engine, and the state the UI
/// reads (url, title, loading, error, history).
///
/// The tab owns its engine callbacks. Screens listen to the tab instead of
/// re-assigning those callbacks: when a screen replaced them, the tab's own
/// url/title stopped updating, which is why tab cards stayed on "New tab" and
/// the address bar showed an unlocked padlock on an https page.
class BrowserTab extends ChangeNotifier {
  final int id;
  final bool isPrivate;
  late final WebViewBrowserEngine engine;

  String url;
  String title;
  bool isLoading = false;
  String? error;
  bool canGoBack = false;
  bool canGoForward = false;

  /// Whether the browser chrome should be showing. Driven by page scrolling,
  /// kept separate from [notifyListeners] so a scroll never rebuilds the page.
  final ValueNotifier<bool> chromeVisible = ValueNotifier(true);

  int _lastScrollY = 0;

  BrowserTab(this.id, {this.isPrivate = false, this.url = '', this.title = ''}) {
    engine = WebViewBrowserEngine(initialUrl: url);
    engine
      ..onUrlChanged = (u) {
        if (u == url) return;
        // A new document: the old page's title no longer describes this tab.
        if (_stripFragment(u) != _stripFragment(url)) title = '';
        url = u;
        notifyListeners();
      }
      ..onTitleChanged = (t) {
        if (t == title) return;
        title = t;
        notifyListeners();
      }
      ..onLoadingChanged = (l) {
        isLoading = l;
        if (l) error = null;
        notifyListeners();
        if (!l) _syncHistory();
      }
      ..onError = (e) {
        error = e;
        isLoading = false;
        notifyListeners();
      }
      ..onScroll = _onScroll;
  }

  /// Load a URL through this tab, clearing any error state first.
  Future<void> load(String target) async {
    error = null;
    isLoading = true;
    notifyListeners();
    await engine.loadUrl(target);
  }

  Future<void> reload() async {
    error = null;
    notifyListeners();
    await engine.reload();
  }

  Future<void> goBack() async {
    await engine.goBack();
    await _syncHistory();
  }

  Future<void> goForward() async {
    await engine.goForward();
    await _syncHistory();
  }

  Future<void> _syncHistory() async {
    final back = await engine.canGoBack();
    final forward = await engine.canGoForward();
    if (back == canGoBack && forward == canGoForward) return;
    canGoBack = back;
    canGoForward = forward;
    notifyListeners();
  }

  /// Hide the chrome while the page scrolls down, bring it back on the way up
  /// or at the very top.
  void _onScroll(int y) {
    final dy = y - _lastScrollY;
    if (dy.abs() < 12) return;
    _lastScrollY = y;
    chromeVisible.value = dy < 0 || y <= 0;
  }

  void showChrome() => chromeVisible.value = true;

  /// What the tab grid shows: page title, else the bare host, else "New tab".
  String get label {
    if (title.isNotEmpty) return title;
    if (url.isEmpty) return 'New tab';
    return host.isEmpty ? url : host;
  }

  /// Host without scheme or path — also used by the address bar's lock row.
  String get host {
    final uri = Uri.tryParse(url);
    if (uri == null || uri.host.isEmpty) {
      return url.replaceFirst(RegExp(r'^https?://'), '').split('/').first;
    }
    return uri.host;
  }

  bool get isSecure => url.startsWith('https://');

  static String _stripFragment(String u) => u.split('#').first;

  @override
  void dispose() {
    chromeVisible.dispose();
    engine.dispose();
    super.dispose();
  }
}
