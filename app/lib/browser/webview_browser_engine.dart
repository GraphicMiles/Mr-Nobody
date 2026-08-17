import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'browser_engine.dart';

/// V1 [BrowserEngine]: a visible WebView rendered via webview_flutter's
/// Platform View (Android System WebView underneath). This is a rendering
/// surface, not the engine — navigation, loading/error state, and URL/title
/// tracking are all owned here, so callers never touch the raw controller.
///
/// When no WebView platform is registered (widget tests, the web design
/// preview, a desktop host) the engine stays [isAvailable] == false and simply
/// tracks state without rendering, instead of throwing and taking the whole
/// screen down with it.
class WebViewBrowserEngine implements BrowserEngine {
  WebViewController? _controller;

  @override
  ValueChanged<bool>? onLoadingChanged;
  @override
  ValueChanged<String>? onUrlChanged;
  @override
  ValueChanged<String>? onTitleChanged;
  @override
  ValueChanged<String>? onError;

  WebViewBrowserEngine({String initialUrl = ''}) {
    try {
      _controller = WebViewController()
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..setBackgroundColor(const Color(0xFF000000))
        ..setNavigationDelegate(NavigationDelegate(
          onPageStarted: (url) {
            onLoadingChanged?.call(true);
            onUrlChanged?.call(url);
          },
          onPageFinished: (url) async {
            onLoadingChanged?.call(false);
            onUrlChanged?.call(url);
            final t = await _controller?.getTitle();
            if (t != null && t.isNotEmpty) onTitleChanged?.call(t);
          },
          onWebResourceError: (error) {
            onLoadingChanged?.call(false);
            // A sub-resource (image/script) failing is not fatal; only surface
            // main-frame errors so we never show a spurious "page failed" state.
            if (error.isForMainFrame == true) {
              onError?.call(error.description);
            }
          },
        ));
    } catch (_) {
      _controller = null; // no WebView platform on this host
    }
    if (initialUrl.isNotEmpty) {
      loadUrl(initialUrl);
    }
  }

  /// Whether a real WebView is backing this engine.
  bool get isAvailable => _controller != null;

  @override
  WebViewController get controller => _controller!;

  @override
  Future<void> loadUrl(String url) async {
    onUrlChanged?.call(url);
    final c = _controller;
    if (c == null) return;
    await c.loadRequest(Uri.parse(url));
  }

  @override
  Future<void> reload() async => _controller?.reload();

  @override
  Future<bool> canGoBack() async => await _controller?.canGoBack() ?? false;

  @override
  Future<void> goBack() async => _controller?.goBack();

  @override
  Future<bool> canGoForward() async => await _controller?.canGoForward() ?? false;

  @override
  Future<void> goForward() async => _controller?.goForward();

  @override
  Future<String?> currentUrl() async => _controller?.currentUrl();

  @override
  Future<String?> title() async => _controller?.getTitle();

  @override
  void dispose() {
    // The controller holds no resources we must release beyond the view.
    _controller = null;
  }
}
