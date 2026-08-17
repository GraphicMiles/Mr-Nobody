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

  List<String> get entries => List.unmodifiable(_entries);
  int get count => _entries.length;

  void add(String message) {
    if (message.trim().isEmpty) return;
    _entries.add(message.trim());
    if (_entries.length > 50) _entries.removeAt(0);
    notifyListeners();
  }

  /// Record a failed platform call without ever crashing the UI over it.
  void addError(Object error, [StackTrace? _]) => add(error.toString());

  void clear() {
    _entries.clear();
    notifyListeners();
  }

  /// The clipboard payload for the overlay's COPY button.
  String get dump => _entries.isEmpty ? 'no errors' : _entries.join('\n');
}
