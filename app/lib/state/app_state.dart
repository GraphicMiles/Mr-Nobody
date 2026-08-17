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
    AiProviderOption('local', 'Local (on-device)', ''),
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
  String profile = 'BALANCED';
  String providerId = 'local';
  bool loaded = false;

  static const profiles = ['BALANCED', 'STRICT', 'MAXIMUM'];

  String get profileLabel => _title(profile);
  String get providerLabel => AiProviderOption.byId(providerId).shortName;
  String get terminalLabel => terminal ? 'on' : 'off';

  static String _title(String v) =>
      v.isEmpty ? v : v[0].toUpperCase() + v.substring(1).toLowerCase();

  Future<void> load() async {
    try {
      final s = await NativeBridge.getSettings();
      history = s['history'] as bool? ?? history;
      js = s['js'] as bool? ?? js;
      suggestions = s['suggestions'] as bool? ?? suggestions;
      terminal = s['terminal'] as bool? ?? terminal;
      profile = (s['profile'] as String? ?? profile).toUpperCase();
      providerId = s['provider'] as String? ?? providerId;
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
  Future<void> setProfile(String v) => _set('profile', v, () => profile = v);
  Future<void> setProvider(String v) => _set('provider', v, () => providerId = v);

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
