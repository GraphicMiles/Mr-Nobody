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

  String get label => title.isNotEmpty ? title : (url.isNotEmpty ? url : 'New tab');
}
