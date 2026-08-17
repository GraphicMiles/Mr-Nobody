import 'webview_browser_engine.dart';

/// One browser tab — stable identity + its own engine/controller. Private tabs
/// never record history (enforced by the core on the visible path's host).
class BrowserTab {
  final int id;
  final bool isPrivate;
  late final WebViewBrowserEngine engine;
  String url;
  String title;

  BrowserTab(this.id, {this.isPrivate = false, this.url = '', this.title = ''}) {
    engine = WebViewBrowserEngine(initialUrl: url);
    engine.onUrlChanged = (u) => url = u;
    engine.onTitleChanged = (t) => title = t;
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
}
