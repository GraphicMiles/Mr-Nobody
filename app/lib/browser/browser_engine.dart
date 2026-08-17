import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// The engine-independent browser capability used by the visible browser and,
/// in the agent path, by the BrowserTool. The V1 implementation is
/// [WebViewBrowserEngine] (wrapping webview_flutter → Android WebView). Nothing
/// in the UI depends on the concrete engine — it depends on this interface.
abstract class BrowserEngine {
  /// The underlying controller, for the WebViewWidget to render.
  WebViewController get controller;

  Future<void> loadUrl(String url);
  Future<void> reload();
  Future<bool> canGoBack();
  Future<void> goBack();
  Future<bool> canGoForward();
  Future<void> goForward();
  Future<String?> currentUrl();
  Future<String?> title();

  /// Called with true/false as navigation begins/ends (loading state).
  ValueChanged<bool>? onLoadingChanged;

  /// Called with the committed URL on navigation.
  ValueChanged<String>? onUrlChanged;

  /// Called with the page title once loaded.
  ValueChanged<String>? onTitleChanged;

  /// Called when a resource fails to load (error state).
  ValueChanged<String>? onError;

  void dispose();
}
