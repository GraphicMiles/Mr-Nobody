import 'package:flutter/services.dart';

/// Bridge to the Java core (agent engine, tools, task store, filter engine,
/// deep-link routing) via a MethodChannel on the Android host MainActivity.
///
/// The UI never talks to the core directly; every call goes through here, so
/// the core stays behind its existing interfaces (BrowserEngine, AgentEngine,
/// TaskStore, FilterEngine) and can be swapped independently.
class NativeBridge {
  static const MethodChannel _ch = MethodChannel('mrnobody/core');

  /// Run a task through the agent core (deterministic → search/extract).
  static Future<Map<String, dynamic>> runTask(String instruction) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('runTask', {'instruction': instruction}));
  }

  /// Fetch the recent task list for the Tasks screen.
  static Future<List<Map<String, dynamic>>> recentTasks() async {
    final r = await _ch.invokeMethod('recentTasks');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Privacy counters for the dashboard.
  static Future<Map<String, dynamic>> privacyStats() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('privacyStats'));
  }

  /// Whether history is enabled (privacy dashboard + settings mirror).
  static Future<bool> isHistoryEnabled() async {
    return await _ch.invokeMethod('isHistoryEnabled');
  }
}
