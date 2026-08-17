/// Deterministic intent classification for the unified input — a Dart mirror of
/// the Java IntentRouter (same rules, pure logic, unit-testable).
enum IntentType { url, search, task }

class IntentRouter {
  static const _verbs = {
    'find', 'get', 'fetch', 'download', 'summarize', 'summarise', 'compare',
    'check', 'monitor', 'track', 'extract', 'collect', 'search for',
    'open and', 'every', 'watch', 'scrape', 'send', 'buy', 'order', 'look up',
  };

  static final _scheme = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*://');
  static final _ip = RegExp(r'^\d{1,3}(\.\d{1,3}){3}(:\d+)?$');
  static final _localhost = RegExp(r'^localhost(:\d+)?$');

  static IntentType route(String input) {
    final s = input.trim();
    if (s.isEmpty) return IntentType.search;
    if (_scheme.hasMatch(s)) return IntentType.url;
    if (_ip.hasMatch(s) || _localhost.hasMatch(s)) return IntentType.url;
    if (_looksLikeDomain(s)) return IntentType.url;

    final lower = s.toLowerCase();
    for (final v in _verbs) {
      if (lower == v || lower.startsWith('$v ')) return IntentType.task;
    }
    if (lower.contains(' and ')) return IntentType.task;
    return IntentType.search;
  }

  /// Normalize input into a full URL: bare domains get https://, else it's a
  /// search query for the configured engine (DuckDuckGo default).
  static String toUrl(String input) {
    final s = input.trim();
    if (_scheme.hasMatch(s)) return s;
    if (_ip.hasMatch(s) || _localhost.hasMatch(s)) return 'http://$s';
    if (_looksLikeDomain(s)) return 'https://$s';
    return 'https://duckduckgo.com/?q=${Uri.encodeComponent(s)}';
  }

  static bool _looksLikeDomain(String s) {
    if (s.contains(' ')) return false;
    final slash = s.indexOf('/');
    var host = slash >= 0 ? s.substring(0, slash) : s;
    final colon = host.indexOf(':');
    if (colon >= 0) host = host.substring(0, colon);
    final dot = host.indexOf('.');
    if (dot <= 0 || dot == host.length - 1) return false;
    return RegExp(r'^[a-zA-Z]{2,}$').hasMatch(host.substring(host.lastIndexOf('.') + 1));
  }
}
