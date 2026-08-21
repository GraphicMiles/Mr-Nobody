/// Deterministic intent classification for the unified input — a Dart mirror of
/// the Java IntentRouter (same rules, pure logic, unit-testable).
enum IntentType { url, search, task }

class IntentRouter {
  static const _verbs = {
    'find', 'get', 'fetch', 'download', 'summarize', 'summarise', 'compare',
    'check', 'monitor', 'track', 'extract', 'collect', 'search for',
    'open and', 'every', 'watch', 'scrape', 'send', 'buy', 'order', 'look up',
    // "research the tallest buildings" and "use google search to find …"
    // were observed routing to a raw results page instead of the agent.
    'research', 'read', 'use', 'look for', 'browse for',
  };

  /// Question openers: a natural-language question is an agent task, not a
  /// raw search. "What is X's age" went to a DuckDuckGo results page because
  /// nothing recognised it as a question; it should go to the agent, which
  /// searches and answers instead of dumping a results page.
  static const _questions = {
    'what is', "what's", 'what are', 'what was', 'what were', 'what about',
    'who is', "who's", 'who are', 'who was',
    'how old', 'how much', 'how many', 'how to', 'how do', 'how does',
    'how is', 'how are', 'how long', 'how tall',
    'when is', 'when was', 'when did', 'when does', 'when will',
    'where is', 'where can', 'where do', 'where are',
    'why is', 'why does', 'why are', 'why do',
    'which is', 'which one', 'which are',
    'tell me', 'explain', 'define', 'is', 'are', 'does', 'did', 'can you',
  };

  /// Trailing fact-words: a phrase ending in one of these is a lookup the
  /// agent should handle even without a question word ("hrithik roshan age").
  static const _factWords = {
    'age', 'height', 'birthday', 'birthdate', 'net worth', 'worth', 'price',
    'salary', 'girlfriend', 'wife', 'husband', 'nationality', 'religion',
    'instagram', 'twitter', 'meaning', 'definition', 'capital', 'population',
  };

  static final _scheme = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*://');
  static final _ip = RegExp(r'^\d{1,3}(\.\d{1,3}){3}(:\d+)?$');
  static final _localhost = RegExp(r'^localhost(:\d+)?$');

  static IntentType route(String input) {
    final s = input.trim();
    if (s.isEmpty) return IntentType.search;
    // Slash commands are explicit user overrides: classification heuristics
    // will always miss some phrasing, so "/agent …" must be deterministic.
    final slash = slashCommand(s);
    if (slash != null) return slash;
    if (_scheme.hasMatch(s)) return IntentType.url;
    if (_ip.hasMatch(s) || _localhost.hasMatch(s)) return IntentType.url;
    if (_looksLikeDomain(s)) return IntentType.url;

    final lower = s.toLowerCase();
    for (final v in _verbs) {
      if (lower == v || lower.startsWith('$v ')) return IntentType.task;
    }
    if (lower.contains(' and ')) return IntentType.task;
    for (final q in _questions) {
      if (lower == q || lower.startsWith('$q ')) return IntentType.task;
    }
    // A vague/partial lookup: "hrithik roshan age", "bitcoin price today".
    final words = lower.split(' ');
    if (words.length >= 2 && _factWords.contains(words.last)) {
      return IntentType.task;
    }
    return IntentType.search;
  }

  /// Explicit slash-command routing: `/agent` and `/task` force the agent,
  /// `/download <x>` forces a download task, `/search` forces a plain browser
  /// search, `/open` forces URL handling. Returns null when [input] is not a
  /// slash command.
  static IntentType? slashCommand(String input) {
    final lower = input.trim().toLowerCase();
    if (lower.startsWith('/agent ') || lower.startsWith('/task ') ||
        lower.startsWith('/download ') || lower.startsWith('/dl ')) {
      return IntentType.task;
    }
    if (lower.startsWith('/search ')) return IntentType.search;
    if (lower.startsWith('/open ')) return IntentType.url;
    return null;
  }

  /// The text a slash command carries: `/agent why is the sky blue` →
  /// `why is the sky blue`; `/download <url>` → `download <url>` so the
  /// existing download routing applies. Non-command input returns unchanged.
  static String payload(String input) {
    final s = input.trim();
    final lower = s.toLowerCase();
    for (final prefix in const ['/agent ', '/task ', '/search ', '/open ']) {
      if (lower.startsWith(prefix)) return s.substring(prefix.length).trim();
    }
    if (lower.startsWith('/download ')) {
      return 'download ${s.substring('/download '.length).trim()}';
    }
    if (lower.startsWith('/dl ')) {
      return 'download ${s.substring('/dl '.length).trim()}';
    }
    return s;
  }

  /// Normalize input into a full URL: bare domains get https://, else it's a
  /// search query for the configured engine (DuckDuckGo default).
  static String toUrl(String input) {
    final s = input.trim();
    if (s.toLowerCase().startsWith('http://')) {
      return 'https://${s.substring('http://'.length)}';
    }
    if (_scheme.hasMatch(s)) return s;
    if (_ip.hasMatch(s) || _localhost.hasMatch(s)) return 'https://$s';
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
