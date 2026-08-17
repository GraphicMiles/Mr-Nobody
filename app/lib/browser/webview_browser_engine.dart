import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'browser_engine.dart';

/// V1 [BrowserEngine]: a visible WebView rendered via webview_flutter's
/// Platform View (Android System WebView underneath). This is a rendering
/// surface, not the engine — navigation, loading/error state, and URL/title
/// tracking are all owned here, so callers never touch the raw controller.
class WebViewBrowserEngine implements BrowserEngine {
  late final WebViewController _controller;

  @override
  ValueChanged<bool>? onLoadingChanged;
  @override
  ValueChanged<String>? onUrlChanged;
  @override
  ValueChanged<String>? onTitleChanged;
  @override
  ValueChanged<String>? onError;

  WebViewBrowserEngine({String initialUrl = ''}) {
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
          final t = await _controller.getTitle();
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
    if (initialUrl.isNotEmpty) {
      _controller.loadRequest(Uri.parse(initialUrl));
    }
  }

  @override
  WebViewController get controller => _controller;

  @override
  Future<void> loadUrl(String url) => _controller.loadRequest(Uri.parse(url));

  @override
  Future<void> reload() => _controller.reload();

  @override
  Future<bool> canGoBack() => _controller.canGoBack();

  @override
  Future<void> goBack() => _controller.goBack();

  @override
  Future<bool> canGoForward() => _controller.canGoForward();

  @override
  Future<void> goForward() => _controller.goForward();

  @override
  Future<String?> currentUrl() => _controller.currentUrl();

  @override
  Future<String?> title() => _controller.getTitle();

  @override
  void dispose() {
    // The controller holds no resources we must release beyond the view.
  }
}
