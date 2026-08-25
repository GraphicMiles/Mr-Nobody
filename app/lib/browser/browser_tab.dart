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

  /// Latest transient browser notice (a blocked redirect, popup, or download).
  final ValueNotifier<String?> notice = ValueNotifier(null);

  /// A potentially harmful file waiting for an explicit user decision.
  final ValueNotifier<BrowserDownloadRequest?> downloadApproval = ValueNotifier(null);

  /// Console logs for DevTools — ring buffer
  final ValueNotifier<List<Map<String, dynamic>>> consoleLogs = ValueNotifier(const []);
  static const int maxConsoleLogs = 300;

  /// What the page looks like, for the tab grid. Memory only: never written to
  /// disk, and never captured at all for a private tab.
  Uint8List? thumbnail;
  Uint8List? icon;

  Timer? _captureAfterLoad;
  Timer? _loadingTimeout;
  bool _disposed = false;

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
        // History restores can skip loading callbacks. Query navigation state
        // after the authoritative URL event so Back/Forward stay accurate too.
        unawaited(_syncHistory());
      }
      ..onTitleChanged = (t) {
        if (t == title) return;
        title = t;
        notifyListeners();
      }
      ..onIconChanged = (bytes) {
        icon = bytes;
        notifyListeners();
      }
      ..onLoadingChanged = (l) {
        if (l == isLoading) return;
        isLoading = l;
        if (l) {
          error = null;
          // P0 fix: safety timeout — if native never sends loading=false (previous bug),
          // auto-reset after 25s so tab doesn't stay stuck forever
          _loadingTimeout?.cancel();
          _loadingTimeout = Timer(const Duration(seconds: 25), () {
            if (_disposed) return;
            if (isLoading) {
              isLoading = false;
              notifyListeners();
            }
          });
        } else {
          _loadingTimeout?.cancel();
        }
        notifyListeners();
        if (!l) {
          _syncHistory();
          // Let the page settle before photographing it — a capture taken the
          // instant loading ends is usually a half-painted page.
          _captureAfterLoad?.cancel();
          // Android can report finished before the compositor has painted the
          // retained WebView. Retry a few times so the grid never gets stuck
          // with a white/empty card after a fast tab switch.
          // P0 fix: check disposed before scheduling retry
          _captureAfterLoad = Timer(const Duration(milliseconds: 450), () async {
            if (_disposed) return;
            await captureThumbnail();
            if (thumbnail == null && !_disposed) {
              _captureAfterLoad = Timer(const Duration(milliseconds: 900), () {
                if (_disposed) return;
                captureThumbnail();
              });
            }
          });
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
      ..onNotice = (message) {
        notice.value = message;
      }
      ..onDownload = (name, err) {
        notice.value = err ?? (name == null ? null : 'Downloading $name');
      }
      ..onDownloadApproval = (request) {
        downloadApproval.value = request;
      }
      ..onConsole = (entry) {
        if (_disposed) return;
        final current = List<Map<String, dynamic>>.from(consoleLogs.value);
        current.add(entry);
        if (current.length > maxConsoleLogs) {
          current.removeRange(0, current.length - maxConsoleLogs);
        }
        consoleLogs.value = current;
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
  /// P0 fix: skip if disposed, and debounce rapid calls
  DateTime? _lastCapture;
  Future<void> captureThumbnail() async {
    if (_disposed || isPrivate) return;
    final now = DateTime.now();
    if (_lastCapture != null && now.difference(_lastCapture!).inMilliseconds < 800) return;
    _lastCapture = now;
    final shot = await engine.captureThumbnail();
    if (_disposed) return;
    if (shot == null || shot.isEmpty) return;
    // P0 fix: limit thumbnail size to 150KB to avoid memory pressure
    if (shot.length > 150 * 1024) return;
    thumbnail = shot;
    notifyListeners();
  }

  Future<String?> evalJs(String js) => engine.evalJs(js);
  Future<String?> getHtml() => engine.getHtml();
  Future<List<Map<String, dynamic>>> getConsoleFromNative() => engine.getConsoleLogs();
  Future<void> clearConsole() async {
    consoleLogs.value = const [];
    await engine.clearConsole();
  }
  Future<void> injectEruda() => engine.injectEruda();

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

  /// Awaitable native teardown used by Clear Data after this model is removed.
  Future<void> releaseNativeOwnership() => engine.releaseNativeOwnership();

  @override
  void dispose() {
    _disposed = true;
    _captureAfterLoad?.cancel();
    _loadingTimeout?.cancel();
    thumbnail = null;
    icon = null;
    chromeVisible.dispose();
    notice.dispose();
    downloadApproval.dispose();
    consoleLogs.dispose();
    engine.dispose();
    super.dispose();
  }
}
