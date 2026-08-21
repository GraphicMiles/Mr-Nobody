import 'dart:async';

import 'package:flutter/foundation.dart';
import '../bridge/native_bridge.dart';
import 'error_log.dart';

/// An AI provider the user can point the agent at.
///
/// "Local" keeps everything on-device; the rest are user-configured remote
/// providers. Basic browsing never needs any of them (V1 §11, V2 §19).
class AiProviderOption {
  final String id;
  final String name;
  final String tag;

  const AiProviderOption(this.id, this.name, this.tag);

  static const all = [
    AiProviderOption('local', 'Local (no model)', 'on-device'),
    AiProviderOption('gemini', 'Gemini', 'free tier'),
    AiProviderOption('groq', 'Groq', 'free tier'),
    AiProviderOption('openai', 'OpenAI-compatible', 'OpenRouter :free'),
  ];

  static AiProviderOption byId(String id) =>
      all.firstWhere((p) => p.id == id, orElse: () => all.first);

  /// Short label for the Settings value column.
  String get shortName => name.split(' ').first;
}

/// User-facing settings, mirrored from the Java core (`Settings.java`) so every
/// screen reads one source of truth and writes straight through to disk.
///
/// The core is the owner: this object never invents a value. When the platform
/// channel is unavailable (unit tests, or a hot-restart before the host is
/// ready) it keeps the privacy-first defaults and records the failure in the
/// debug overlay instead of silently pretending.
class AppState extends ChangeNotifier {
  AppState._();
  static final AppState instance = AppState._();

  // Privacy-first defaults, identical to Settings.java.
  bool history = false;
  bool js = true;
  bool suggestions = false;
  bool terminal = false;
  bool blocking = true;
  bool paramStripping = true;
  String profile = 'BALANCED';
  String providerId = 'local';
  String privacyMode = 'NORMAL';
  String searchEngine = 'https://duckduckgo.com/?q=';
  String approvalMode = 'CAUTIOUS';
  String resourcePolicy = 'OFF';
  bool loaded = false;

  static const profiles = ['BALANCED', 'STRICT', 'MAXIMUM'];
  static const privacyModes = ['NORMAL', 'PRIVATE', 'NOBODY'];

  /// Friendly label → engine URL, mirroring Settings.java. Google is offered
  /// because the agent's SearchProviders already knows how to read it.
  static const searchEngines = <String, String>{
    'DuckDuckGo': 'https://duckduckgo.com/?q=',
    'Bing': 'https://www.bing.com/search?q=',
    'Startpage': 'https://www.startpage.com/sp/search?query=',
    'Google': 'https://www.google.com/search?q=',
  };

  static const approvalModes = ['CAUTIOUS', 'BALANCED', 'TRUSTING'];

  /// Friendly labels matching ApprovalMode.java's own descriptions.
  static const approvalLabels = <String, String>{
    'CAUTIOUS': 'Ask before acting',
    'BALANCED': 'Ask before commands',
    'TRUSTING': 'Don\'t ask',
  };

  String get profileLabel => _title(profile);
  String get providerLabel => AiProviderOption.byId(providerId).shortName;
  String get terminalLabel => terminal ? 'on' : 'off';
  String get privacyModeLabel => _title(privacyMode);
  String get searchEngineLabel => searchEngines.entries
      .firstWhere((e) => e.value == searchEngine, orElse: () => const MapEntry('DuckDuckGo', 'https://duckduckgo.com/?q='))
      .key;
  String get approvalModeLabel => approvalLabels[approvalMode] ?? _title(approvalMode);
  String get resourcePolicyLabel => _title(resourcePolicy);

  static String _title(String v) =>
      v.isEmpty ? v : v[0].toUpperCase() + v.substring(1).toLowerCase();

  Future<void> load() async {
    try {
      final s = await NativeBridge.getSettings();
      history = s['history'] as bool? ?? history;
      js = s['js'] as bool? ?? js;
      suggestions = s['suggestions'] as bool? ?? suggestions;
      terminal = s['terminal'] as bool? ?? terminal;
      blocking = s['blocking'] as bool? ?? blocking;
      paramStripping = s['paramStripping'] as bool? ?? paramStripping;
      profile = (s['profile'] as String? ?? profile).toUpperCase();
      providerId = s['provider'] as String? ?? providerId;
      privacyMode = (s['privacyMode'] as String? ?? privacyMode).toUpperCase();
      searchEngine = s['searchEngine'] as String? ?? searchEngine;
      approvalMode = (s['approvalMode'] as String? ?? approvalMode).toUpperCase();
      resourcePolicy = (s['resourcePolicy'] as String? ?? resourcePolicy).toUpperCase();
    } catch (e) {
      ErrorLog.instance.add('settings load failed: $e');
    } finally {
      loaded = true;
      notifyListeners();
    }
  }

  Future<void> setHistory(bool v) => _set('history', v, () => history = v);
  Future<void> setJs(bool v) => _set('js', v, () => js = v);
  Future<void> setSuggestions(bool v) => _set('suggestions', v, () => suggestions = v);
  Future<void> setTerminal(bool v) => _set('terminal', v, () => terminal = v);
  Future<void> setProvider(String v) => _set('provider', v, () => providerId = v);
  Future<void> setBlocking(bool v) => _set('blocking', v, () => blocking = v);
  Future<void> setParamStripping(bool v) => _set('paramStripping', v, () => paramStripping = v);
  Future<void> setSearchEngine(String v) => _set('searchEngine', v, () => searchEngine = v);
  Future<void> setApprovalMode(String v) => _set('approvalMode', v, () => approvalMode = v);
  Future<void> setResourcePolicy(String v) => _set('resourcePolicy', v, () => resourcePolicy = v);

  Future<void> setProfile(String v) async {
    await _set('profile', v, () => profile = v);
    // The core applies the bundle (JS, fingerprint, blocking…). Reload so
    // the toggles on this screen match what actually changed.
    await load();
  }

  /// Privacy mode uses its own channel (not setSetting), because the core must
  /// report what actually took effect — a refused NOBODY must not look applied.
  ///
  /// Returns the refusal reason when the requested mode did not take, or null
  /// when it did. Callers must show that sentence; toasting the effective
  /// label alone is how "Start Orbot" got thrown away.
  Future<String?> setPrivacyMode(String v) async {
    privacyMode = v;
    notifyListeners();
    try {
      final r = await NativeBridge.applyPrivacyMode(v);
      final effective = (r['effective'] as String? ?? v).toUpperCase();
      final problem = r['problem'] as String?;
      final pending = r['pending'] == true;
      privacyMode = effective;
      notifyListeners();
      await load();
      if (pending) {
        // Built-in Tor is bootstrapping; the core applies the mode itself at
        // the first circuit. Watch for the flip so the UI follows.
        _watchTorStartup();
        return 'Preparing the protected route — Nobody switches on only after '
            'the browser and network route both confirm it. Built-in Tor can take a minute.';
      }
      if (problem != null && problem.isNotEmpty) return problem;
      return null;
    } catch (e) {
      ErrorLog.instance.add('could not apply privacy mode: $e');
      privacyMode = 'NORMAL';
      notifyListeners();
      return 'Could not apply privacy mode.';
    }
  }

  Timer? _torWatch;
  int _torWatchPolls = 0;

  /// Poll the core while the bundled Tor bootstraps. The core is the source
  /// of truth and applies the mode itself; this only keeps the UI honest —
  /// and if the poll dies (screen closed, app backgrounded), the next
  /// [load] shows the right mode anyway.
  void _watchTorStartup() {
    _torWatch?.cancel();
    _torWatchPolls = 0;
    _torWatch = Timer.periodic(const Duration(seconds: 2), (t) async {
      if (++_torWatchPolls > 240) {
        t.cancel();
        return;
      }
      try {
        final s = await NativeBridge.privacyStatus();
        final mode = (s['mode'] as String? ?? '').toUpperCase();
        final pending = s['pending'] == true;
        final problem = s['problem'] as String?;
        if (mode.isNotEmpty && mode != privacyMode) {
          privacyMode = mode;
          notifyListeners();
        }
        if (!pending) {
          t.cancel();
          if (problem != null && problem.isNotEmpty) {
            ErrorLog.instance.add('built-in Tor: $problem');
          }
        }
      } catch (_) {
        t.cancel();
      }
    });
  }

  /// Called when the app comes to the foreground. If Orbot died, Nobody
  /// drops and this returns the sentence to show; otherwise null.
  Future<String?> revalidateRoute() async {
    try {
      final r = await NativeBridge.revalidateRoute();
      final mode = (r['mode'] as String? ?? privacyMode).toUpperCase();
      if (mode != privacyMode) {
        privacyMode = mode;
        notifyListeners();
      }
      final problem = r['problem'] as String?;
      if (problem != null && problem.isNotEmpty) return problem;
      return null;
    } catch (e) {
      ErrorLog.instance.add('could not revalidate route: $e');
      return null;
    }
  }

  Future<void> _set(String key, Object value, VoidCallback apply) async {
    apply();
    notifyListeners();
    try {
      await NativeBridge.setSetting(key, value);
    } catch (e) {
      ErrorLog.instance.add('could not persist $key: $e');
    }
  }
}
