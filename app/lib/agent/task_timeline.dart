import 'dart:convert';

/// User-facing state of one activity the agent actually entered.
enum TimelineState { working, done, recovered, failed, denied, waiting, cancelled }

/// One planner/engine activity. Tool attempts are evidence beneath this row,
/// never the hierarchy itself.
class TimelineActivity {
  final String label;
  final String kind;
  final String reason;
  final TimelineState state;
  final String metric;
  final List<String> detail;
  final int startedAt;
  final int endedAt;

  const TimelineActivity({
    required this.label,
    required this.kind,
    required this.reason,
    required this.state,
    required this.metric,
    required this.detail,
    required this.startedAt,
    required this.endedAt,
  });
}

/// A page whose content was successfully read, in citation order.
class TimelineSource {
  final String title;
  final String domain;
  final String url;

  const TimelineSource({
    required this.title,
    required this.domain,
    required this.url,
  });
}

/// Projection of the append-only core events into an adaptive UI.
///
/// There is deliberately no fixed Search → Read → Answer list here. A row is
/// created only by a `step.changed` event selected by the engine, or—when
/// reading an older log—by a tool call that really happened. Task classes can
/// therefore share a visual language without sharing a seeded pipeline.
class TaskTimeline {
  final List<TimelineActivity> activities;
  final List<TimelineSource> sources;

  const TaskTimeline({required this.activities, required this.sources});

  String get activeLabel => activities.isEmpty ? 'Working' : activities.last.label;

  factory TaskTimeline.fromEvents({
    required List<Map<String, dynamic>> events,
    required String taskStatus,
    String currentStep = '',
  }) {
    final run = _currentRun(events);
    final builders = <_ActivityBuilder>[];
    final calls = <String, _ToolAttempt>{};
    final owners = <String, _ActivityBuilder>{};
    _ActivityBuilder? current;
    var phaseBacked = false;
    var anonymous = 0;

    for (final event in run) {
      final type = event['type'] as String? ?? '';
      final detail = event['detail'] as String? ?? '';
      final at = (event['at'] as num?)?.toInt() ?? 0;

      if (type == 'step.changed') {
        final payload = _object(detail);
        final label = _text(payload, 'label', fallback: detail);
        if (label.isEmpty) continue;
        current = _ActivityBuilder(
          label: label,
          kind: _text(payload, 'kind'),
          reason: _text(payload, 'reason'),
          startedAt: at,
        );
        builders.add(current);
        phaseBacked = true;
        continue;
      }

      if (type == 'tool.call') {
        final parsed = _parseCall(detail, 'legacy-${anonymous++}');
        if (current == null || !phaseBacked) {
          current = _ActivityBuilder(
            label: _semanticLabel(parsed.tool, parsed.action),
            kind: _kind(parsed.tool, parsed.action),
            reason: '',
            startedAt: at,
          );
          builders.add(current);
        }
        parsed.startedAt = at;
        current.tools.add(parsed);
        calls[parsed.id] = parsed;
        owners[parsed.id] = current;
        continue;
      }

      if (type == 'task.finished' || type == 'task.failed') {
        if (current != null && at > current.endedAt) current.endedAt = at;
        continue;
      }

      if (type == 'tool.result' || type == 'tool.denied') {
        final payload = _object(detail);
        final id = _text(payload, 'id');
        _ToolAttempt? call = id.isEmpty ? null : calls[id];
        _ActivityBuilder? owner = id.isEmpty ? null : owners[id];
        if (call == null) {
          // Legacy result strings had no call id. Pair with the latest open
          // attempt, not merely the latest row.
          for (final b in builders.reversed) {
            for (final t in b.tools.reversed) {
              if (t.state == TimelineState.working) {
                call = t;
                owner = b;
                break;
              }
            }
            if (call != null) break;
          }
        }
        if (call == null) continue;
        _applyResult(call, detail, payload,
            deniedEvent: type == 'tool.denied', at: at);
        if (owner != null && at > owner.endedAt) owner.endedAt = at;
      }
    }

    if (builders.isEmpty && currentStep.trim().isNotEmpty) {
      builders.add(_ActivityBuilder(
        label: currentStep.trim(),
        kind: '',
        reason: '',
        startedAt: run.isEmpty
            ? 0
            : ((run.last['at'] as num?)?.toInt() ?? 0),
      ));
    }

    final live = const {'QUEUED', 'RUNNING', 'VERIFYING'}.contains(taskStatus);
    for (var i = 0; i < builders.length; i++) {
      final b = builders[i];
      final isLast = i == builders.length - 1;
      final nextAt = isLast ? 0 : builders[i + 1].startedAt;
      if (b.endedAt == 0 && nextAt > 0) b.endedAt = nextAt;
      b.state = _settledState(b.tools);
      if (!isLast && b.state == TimelineState.working) {
        // A later semantic activity proves this one stopped being active even
        // if an older build omitted its result event.
        b.state = TimelineState.done;
      }
      if (isLast) {
        if (live) {
          b.state = TimelineState.working;
        } else if (taskStatus == 'COMPLETED' &&
            b.state == TimelineState.working) {
          b.state = TimelineState.done;
        } else if (taskStatus == 'WAITING') {
          b.state = TimelineState.waiting;
        } else if (taskStatus == 'FAILED') {
          b.state = TimelineState.failed;
        } else if (taskStatus == 'CANCELLED') {
          b.state = TimelineState.cancelled;
        }
      }
    }

    final sourceMap = <String, TimelineSource>{};
    for (final b in builders) {
      for (final call in b.tools) {
        if (call.state != TimelineState.done) continue;
        final read = call.tool == 'http' && call.action == 'fetch' ||
            call.tool == 'browser' &&
                (call.action == 'fetch' || call.action == 'extract');
        if (!read) continue;
        final url = call.resultUrl.isNotEmpty ? call.resultUrl : call.url;
        final uri = Uri.tryParse(url);
        final domain = (uri?.host ?? '').replaceFirst(RegExp(r'^www\.'), '');
        if (domain.isEmpty) continue;
        sourceMap.putIfAbsent(
          url,
          () => TimelineSource(title: domain, domain: domain, url: url),
        );
      }
    }

    return TaskTimeline(
      activities: [for (final b in builders) b.build()],
      sources: sourceMap.values.toList(),
    );
  }

  /// Only the current execution cycle belongs in the live pipeline. Earlier
  /// answers remain in chat, but their steps do not masquerade as current work.
  static List<Map<String, dynamic>> _currentRun(
      List<Map<String, dynamic>> events) {
    var from = 0;
    for (var i = 0; i < events.length; i++) {
      final type = events[i]['type'] as String? ?? '';
      if (type == 'task.started' || type == 'user.followup') from = i;
    }
    return events.sublist(from);
  }

  static Map<String, dynamic> _object(String raw) {
    if (!raw.trimLeft().startsWith('{')) return const {};
    try {
      final value = jsonDecode(raw);
      return value is Map ? Map<String, dynamic>.from(value) : const {};
    } catch (_) {
      return const {};
    }
  }

  static String _text(Map<String, dynamic> map, String key,
      {String fallback = ''}) {
    final value = map[key];
    if (value == null) return fallback;
    final text = value.toString().trim();
    return text.isEmpty ? fallback : text;
  }

  static _ToolAttempt _parseCall(String raw, String fallbackId) {
    final o = _object(raw);
    if (o.isNotEmpty) {
      return _ToolAttempt(
        id: _text(o, 'id', fallback: fallbackId),
        tool: _text(o, 'tool'),
        action: _text(o, 'action'),
        subject: _text(o, 'subject'),
        url: _text(o, 'url'),
      );
    }

    // Current pre-structured summaries: http.fetch(url=https://...).
    final call = RegExp(r'^([\w-]+)(?:\.([\w-]+))?\((.*)\)$').firstMatch(raw.trim());
    if (call != null) {
      final tool = call.group(1) ?? '';
      final action = call.group(2) ?? _defaultAction(tool);
      final args = call.group(3) ?? '';
      final url = RegExp(r'(?:^|,\s*)url=([^,]+)').firstMatch(args)?.group(1) ?? '';
      final q = RegExp(r'(?:^|,\s*)q=([^,]+)').firstMatch(args)?.group(1) ?? '';
      return _ToolAttempt(
        id: fallbackId,
        tool: tool,
        action: action,
        subject: url.isNotEmpty ? url : q,
        url: url,
      );
    }

    // Earliest logs: `search search bitcoin price` or `browser open URL`.
    final parts = raw.trim().split(RegExp(r'\s+'));
    final tool = parts.isEmpty ? '' : parts.first.toLowerCase();
    final action = parts.length > 1 ? parts[1].toLowerCase() : _defaultAction(tool);
    final subject = parts.length > 2 ? parts.sublist(2).join(' ') : '';
    final url = RegExp(r'https?://[^\s]+').firstMatch(subject)?.group(0) ?? '';
    return _ToolAttempt(
      id: fallbackId,
      tool: tool,
      action: action,
      subject: subject,
      url: url,
    );
  }

  static void _applyResult(
    _ToolAttempt call,
    String raw,
    Map<String, dynamic> o, {
    required bool deniedEvent,
    required int at,
  }) {
    if (o.isNotEmpty) {
      final state = _text(o, 'state');
      call.state = _state(state, deniedEvent: deniedEvent);
      call.durationMs = (o['durationMs'] as num?)?.toInt() ?? 0;
      call.count = (o['count'] as num?)?.toInt();
      call.unit = _text(o, 'unit');
      call.reason = _text(o, 'reason');
      call.resultUrl = _text(o, 'url');
      call.name = _text(o, 'name');
      call.outputStatus = _text(o, 'status');
      call.endedAt = at;
      return;
    }

    final lower = raw.toLowerCase();
    call.state = deniedEvent || lower.contains('refused') || lower.contains('denied')
        ? TimelineState.denied
        : lower.contains(' ok ')
            ? TimelineState.done
            : lower.contains('waiting') || lower.contains('approval')
                ? TimelineState.waiting
                : TimelineState.failed;
    final duration = RegExp(r'in (\d+)ms').firstMatch(raw)?.group(1);
    call.durationMs = int.tryParse(duration ?? '') ?? 0;
    final reasonAt = raw.indexOf('—');
    if (reasonAt >= 0) call.reason = raw.substring(reasonAt + 1).trim();
    call.endedAt = at;
  }

  static TimelineState _state(String value, {required bool deniedEvent}) {
    if (deniedEvent || value == 'denied') return TimelineState.denied;
    if (value == 'waiting') return TimelineState.waiting;
    if (value == 'done' || value == 'success') return TimelineState.done;
    if (value == 'recovered') return TimelineState.recovered;
    if (value == 'cancelled') return TimelineState.cancelled;
    return TimelineState.failed;
  }

  static TimelineState _settledState(List<_ToolAttempt> tools) {
    if (tools.isEmpty) return TimelineState.done;
    final states = tools.map((t) => t.state).toSet();
    if (states.contains(TimelineState.waiting)) return TimelineState.waiting;
    if (states.contains(TimelineState.denied)) return TimelineState.denied;
    final succeeded = states.contains(TimelineState.done);
    final failed = states.contains(TimelineState.failed);
    if (succeeded && failed) return TimelineState.recovered;
    if (failed) return TimelineState.failed;
    if (states.contains(TimelineState.working)) return TimelineState.working;
    return TimelineState.done;
  }

  static String _kind(String tool, String action) =>
      action.isEmpty || action == tool ? tool : '$tool.$action';

  static String _defaultAction(String tool) {
    switch (tool) {
      case 'search':
        return 'search';
      case 'http':
        return 'fetch';
      case 'download':
        return 'download';
      default:
        return tool;
    }
  }

  static String _semanticLabel(String tool, String action) {
    switch (tool) {
      case 'search':
        return 'Searching broadly';
      case 'http':
        return 'Reading source pages';
      case 'download':
        return 'Downloading the file';
      case 'memory':
        return 'Checking previous work';
      case 'terminal':
        return 'Using the workspace terminal';
      case 'browser':
        if (action == 'submit') return 'Submitting the form';
        if (action == 'upload') return 'Preparing the upload';
        if (action == 'save') return 'Saving the file';
        if (const {'click', 'type', 'select'}.contains(action)) {
          return 'Interacting with the page';
        }
        if (const {'links', 'forms', 'title', 'review'}.contains(action)) {
          return 'Inspecting the page';
        }
        return 'Reading the page';
      default:
        return tool.isEmpty ? 'Working' : _title(tool);
    }
  }

  static String _title(String text) => text.isEmpty
      ? text
      : '${text[0].toUpperCase()}${text.substring(1).replaceAll('_', ' ')}';
}

class _ActivityBuilder {
  final String label;
  final String kind;
  final String reason;
  final int startedAt;
  int endedAt = 0;
  TimelineState state = TimelineState.working;
  final List<_ToolAttempt> tools = [];

  _ActivityBuilder({
    required this.label,
    required this.kind,
    required this.reason,
    required this.startedAt,
  });

  TimelineActivity build() {
    final detail = <String>[];
    if (reason.isNotEmpty) detail.add('Decision · $reason');
    for (final tool in tools) {
      detail.add(tool.description);
    }
    return TimelineActivity(
      label: label,
      kind: kind,
      reason: reason,
      state: state,
      metric: _metric(),
      detail: detail,
      startedAt: startedAt,
      endedAt: endedAt,
    );
  }

  String _metric() {
    for (final t in tools.reversed) {
      if (t.count != null && t.unit.isNotEmpty) {
        return '${t.count} ${t.count == 1 ? _singular(t.unit) : t.unit}';
      }
      if (t.name.isNotEmpty) return t.name;
    }
    final readDomains = <String>{};
    for (final t in tools) {
      if (t.state != TimelineState.done) continue;
      final read = t.tool == 'http' && t.action == 'fetch' ||
          t.tool == 'browser' && (t.action == 'fetch' || t.action == 'extract');
      if (!read) continue;
      final url = t.resultUrl.isNotEmpty ? t.resultUrl : t.url;
      final host = Uri.tryParse(url)?.host.replaceFirst(RegExp(r'^www\.'), '') ?? '';
      if (host.isNotEmpty) readDomains.add(host);
    }
    if (readDomains.length == 1) return readDomains.first;
    if (readDomains.length > 1) return '${readDomains.length} sources';
    if (tools.length > 1) return '${tools.length} attempts';
    if (tools.length == 1 && tools.first.subject.isNotEmpty) {
      final subject = tools.first.subject;
      final host = Uri.tryParse(subject)?.host.replaceFirst(RegExp(r'^www\.'), '') ?? '';
      return host.isNotEmpty ? host : _short(subject, 34);
    }
    return '';
  }

  static String _singular(String value) {
    if (value == 'candidates') return 'candidate';
    if (value == 'matches') return 'match';
    if (value == 'links') return 'link';
    if (value == 'locations') return 'location';
    return value.endsWith('s') ? value.substring(0, value.length - 1) : value;
  }
}

class _ToolAttempt {
  final String id;
  final String tool;
  final String action;
  final String subject;
  final String url;
  String resultUrl = '';
  String name = '';
  String outputStatus = '';
  String reason = '';
  String unit = '';
  int? count;
  int durationMs = 0;
  int startedAt = 0;
  int endedAt = 0;
  TimelineState state = TimelineState.working;

  _ToolAttempt({
    required this.id,
    required this.tool,
    required this.action,
    required this.subject,
    required this.url,
  });

  String get description {
    final parts = <String>[
      [tool, action].where((s) => s.isNotEmpty).join('.'),
      _stateLabel(state),
    ];
    if (count != null && unit.isNotEmpty) parts.add('$count $unit');
    if (durationMs > 0) parts.add(_duration(durationMs));
    if (reason.isNotEmpty) parts.add(reason);
    return parts.where((s) => s.isNotEmpty).join(' · ');
  }

  static String _stateLabel(TimelineState state) {
    switch (state) {
      case TimelineState.working:
        return 'working';
      case TimelineState.done:
        return 'done';
      case TimelineState.recovered:
        return 'recovered';
      case TimelineState.failed:
        return 'failed';
      case TimelineState.denied:
        return 'denied';
      case TimelineState.waiting:
        return 'waiting';
      case TimelineState.cancelled:
        return 'cancelled';
    }
  }

  static String _duration(int ms) =>
      ms < 1000 ? '${ms}ms' : '${(ms / 1000).toStringAsFixed(1)}s';
}

String _short(String value, int max) =>
    value.length <= max ? value : '${value.substring(0, max - 1)}…';
