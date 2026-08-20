import 'dart:async';
import 'package:flutter/foundation.dart';

import 'browser_engine.dart';
import 'native_webview_engine.dart';

/// Live counts of what the filter engine refused on the current page.
class BlockedCounts {
  final int ads;
  final int trackers;

  const BlockedCounts({this.ads = 0, this.trackers = 0});

  int get total => ads + trackers;
}

/// One browser tab — stable identity, its own engine, and the state the UI
/// reads (url, title, loading, error, history, blocked counts).
///
/// The tab owns its engine callbacks. Screens listen to the tab instead of
/// re-assigning those callbacks: when a screen replaced them, the tab's own
/// url/title stopped updating, which is why tab cards stayed on "New tab" and
/// the address bar showed an unlocked padlock on an https page.
class BrowserTab extends ChangeNotifier {
  final int id;
  final bool isPrivate;
  late final BrowserEngine engine;

  String url;
  String title;
  bool isLoading = false;
  String? error;
  bool canGoBack = false;
  bool canGoForward = false;
  BlockedCounts blocked = const BlockedCounts();

  /// Whether the browser chrome should be showing. Driven by page scrolling,
  /// kept separate from [notifyListeners] so a scroll never rebuilds the page.
  final ValueNotifier<bool> chromeVisible = ValueNotifier(true);

  /// Latest download notice: the file name, or an error to show the user.
  final ValueNotifier<String?> downloadNotice = ValueNotifier(null);

  /// A potentially harmful file waiting for an explicit user decision.
  final ValueNotifier<BrowserDownloadRequest?> downloadApproval = ValueNotifier(null);

  /// What the page looks like, for the tab grid. Memory only: never written to
  /// disk, and never captured at all for a private tab.
  Uint8List? thumbnail;

  Timer? _captureAfterLoad;

  int _lastScrollY = 0;

  /// Injected in tests; production always builds the platform-view engine.
  static BrowserEngine Function({required int tabId, required String url, required bool isPrivate})?
      engineFactory;

  BrowserTab(this.id, {this.isPrivate = false, this.url = '', this.title = ''}) {
    engine = (engineFactory ?? _defaultEngine)(tabId: id, url: url, isPrivate: isPrivate);
    engine
      ..onUrlChanged = (u) {
        if (u == url) return;
        // A new document: the old page's title no longer describes this tab,
        // and its blocked counters start again.
        if (_stripFragment(u) != _stripFragment(url)) {
          title = '';
          blocked = const BlockedCounts();
        }
        url = u;
        notifyListeners();
      }
      ..onTitleChanged = (t) {
        if (t == title) return;
        title = t;
        notifyListeners();
      }
      ..onLoadingChanged = (l) {
        if (l == isLoading) return;
        isLoading = l;
        if (l) error = null;
        notifyListeners();
        if (!l) {
          _syncHistory();
          // Let the page settle before photographing it — a capture taken the
          // instant loading ends is usually a half-painted page.
          _captureAfterLoad?.cancel();
          _captureAfterLoad = Timer(const Duration(milliseconds: 700), captureThumbnail);
        }
      }
      ..onError = (e) {
        error = e;
        isLoading = false;
        notifyListeners();
      }
      ..onScroll = _onScroll
      ..onBlockedCountChanged = (ads, trackers) {
        if (ads == blocked.ads && trackers == blocked.trackers) return;
        blocked = BlockedCounts(ads: ads, trackers: trackers);
        notifyListeners();
      }
      ..onDownload = (name, err) {
        downloadNotice.value = err ?? (name == null ? null : 'Downloading $name');
      }
      ..onDownloadApproval = (request) {
        downloadApproval.value = request;
      };
  }

  static BrowserEngine _defaultEngine(
          {required int tabId, required String url, required bool isPrivate}) =>
      NativeWebViewEngine(tabId: tabId, initialUrl: url, isPrivate: isPrivate);

  /// Load a URL through this tab, clearing any error state first.
  Future<void> load(String target) async {
    error = null;
    isLoading = true;
    notifyListeners();
    await engine.loadUrl(target);
  }

  Future<void> reload() async {
    error = null;
    isLoading = true;
    notifyListeners();
    await engine.reload();
  }

  Future<bool> resolveDownload(BrowserDownloadRequest request, bool allow) async {
    if (downloadApproval.value?.id == request.id) downloadApproval.value = null;
    return engine.resolveDownload(request.id, allow);
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

  /// Refresh the tab-grid preview. Cheap enough to call when leaving the page.
  Future<void> captureThumbnail() async {
    if (isPrivate) return;
    final shot = await engine.captureThumbnail();
    if (shot == null || shot.isEmpty) return;
    thumbnail = shot;
    notifyListeners();
  }

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
    _captureAfterLoad?.cancel();
    thumbnail = null;
    chromeVisible.dispose();
    downloadNotice.dispose();
    downloadApproval.dispose();
    engine.dispose();
    super.dispose();
  }
}
