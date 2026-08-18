import 'package:flutter/foundation.dart';

/// In-app error log behind the ⓘ overlay in every screen of the wireframe.
///
/// The debug overlay exists because this app ships no analytics and no crash
/// reporter — if something breaks on a user's device, the only way they can
/// tell us is by opening the overlay and copying the log themselves.
class ErrorLog extends ChangeNotifier {
  ErrorLog._();
  static final ErrorLog instance = ErrorLog._();

  final List<String> _entries = [];

  /// Entries the Java core recorded (tool failures, AI errors, blocked writes).
  /// Kept separate so a refresh replaces them instead of stacking duplicates.
  List<String> _native = const [];

  List<String> get entries => List.unmodifiable([..._native, ..._entries]);

  int get count => _native.length + _entries.length;

  void add(String message) {
    if (message.trim().isEmpty) return;
    _entries.add(message.trim());
    if (_entries.length > 50) _entries.removeAt(0);
    notifyListeners();
  }

  /// Record a failed platform call without ever crashing the UI over it.
  void addError(Object error, [StackTrace? _]) => add(error.toString());

  /// Replace the core's entries with its current log.
  ///
  /// Half of what can go wrong happens in Java — an AI provider returning 404,
  /// a tool breaking its contract, a task failing in a background worker — and
  /// none of it reaches a Dart try/catch. Without this the overlay reported
  /// zero errors while the user was looking at one.
  void setNative(List<String> entries) {
    if (_sameAsNative(entries)) return;
    _native = List.unmodifiable(entries);
    notifyListeners();
  }

  bool _sameAsNative(List<String> other) {
    if (other.length != _native.length) return false;
    for (var i = 0; i < other.length; i++) {
      if (other[i] != _native[i]) return false;
    }
    return true;
  }

  void clear() {
    _entries.clear();
    _native = const [];
    notifyListeners();
  }

  /// The clipboard payload for the overlay's COPY button.
  String get dump => count == 0 ? 'no errors' : entries.join('\n');
}
