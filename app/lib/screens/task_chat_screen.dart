import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/agent_response.dart';
import '../widgets/answer_document.dart';
import '../widgets/answer_view.dart';
import '../widgets/toast.dart';

/// A task as a conversation.
///
/// Replaces the old task detail screen, which drew a five-step plan —
/// Search, Open candidates, Extract prices, Verify, Compare — that was
/// hardcoded in Dart and identical for every task. A download task displayed
/// "Extract prices", and the progress bar was a fraction of that fiction.
/// Everything here comes from the task record and the append-only event log,
/// so the screen can only show work that actually happened.
///
/// Two ways in, both landing here: typing a prompt in the address bar, or
/// tapping a row in Tasks.
class TaskChatScreen extends StatefulWidget {
  final int? taskId;
  final String title;

  /// The instruction, shown as the first user message. Falls back to [title].
  final String? instruction;

  /// Open a URL in a visible tab. Used when a file upload or login grant
  /// needs the user to finish the step themselves.
  final void Function(String url)? onOpenUrl;

  const TaskChatScreen({
    super.key,
    required this.title,
    this.taskId,
    this.instruction,
    this.onOpenUrl,
  });

  @override
  State<TaskChatScreen> createState() => _TaskChatScreenState();
}

class _TaskChatScreenState extends State<TaskChatScreen> {
  static const _live = {'QUEUED', 'RUNNING', 'VERIFYING', 'WAITING'};

  /// The task answer stream, pushed from the worker via TaskStreamHub. See
  /// the Java side; this is how a remote provider's tokens reach the chat as
  /// they are generated.
  static const _stream = EventChannel('mrnobody/task-stream');

  final _scroll = ScrollController();
  final _input = TextEditingController();

  Timer? _poll;
  Timer? _reveal;
  StreamSubscription<dynamic>? _streamSub;

  Map<String, dynamic> _task = const {};
  List<Map<String, dynamic>> _events = const [];

  /// When this run started, for the elapsed counter. Taken from the task's
  /// own timestamp where there is one, so reopening a running task shows how
  /// long it has actually been going rather than restarting from zero.
  DateTime _startedAt = DateTime.now();

  /// The finished answer, split for the word-by-word reveal.
  List<StreamToken> _tokens = const [];

  /// How much of [_tokens] has been revealed.
  int _revealed = 0;

  /// The result text the reveal was built from, so a poll that returns the
  /// same answer does not restart the animation.
  String _revealedFrom = '';

  /// The answer text arrived live through the stream, so the final result's
  /// already-shown prefix is not re-revealed. Cleared once the result lands.
  String _streamBuf = '';

  /// A live stream is in flight (tokens may still arrive).
  bool _streaming = false;

  @override
  void initState() {
    super.initState();
    if (widget.taskId != null) {
      _refresh();
      _poll = Timer.periodic(const Duration(milliseconds: 600), (_) => _refresh());
      _listenStream();
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    _reveal?.cancel();
    _streamSub?.cancel();
    _scroll.dispose();
    _input.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------------ stream

  /// Subscribe to the task answer stream, so a remote provider's tokens land
  /// as they are generated rather than all at once when the task completes.
  ///
  /// The stream is best-effort: when it is absent (tests, a build without the
  /// native handler) the poll and the timed reveal below still deliver the
  /// full answer, so a missing stream must never read as an error.
  void _listenStream() {
    final id = widget.taskId;
    if (id == null) return;
    _streamSub = _stream.receiveBroadcastStream(id).listen(
      _onStreamEvent,
      onError: (Object _) {
        // No native handler: fall through to the poll, which already has the
        // answer covered. Deliberately not logged — this is a normal state.
      },
    );
  }

  void _onStreamEvent(dynamic event) {
    if (!mounted || event is! Map) return;
    final map = Map<String, dynamic>.from(event);
    if ((map['taskId'] as num?)?.toInt() != widget.taskId) return;
    switch (map['type']) {
      case 'token':
        // The final result, once it lands, is authoritative (it carries the
        // verification notes the stream does not), so ignore late tokens that
        // race it.
        if (_revealedFrom.isNotEmpty) break;
        _streamBuf += map['text'] as String? ?? '';
        _streaming = true;
        setState(() {
          _tokens = _tokenise(_streamBuf);
          _revealed = _tokens.length;
        });
        _autoScroll();
        break;
      case 'done':
        _streaming = false;
        break;
      case 'error':
        _streaming = false;
        break;
      default:
        break;
    }
  }

  // ------------------------------------------------------------------ data

  Future<void> _refresh() async {
    final id = widget.taskId;
    if (id == null) return;

    final task = await NativeBridge.guard(
      () => NativeBridge.task(id),
      null,
      'task unavailable',
    );
    final events = await NativeBridge.guard(
      () => NativeBridge.taskEvents(id),
      const <Map<String, dynamic>>[],
      'task events unavailable',
    );
    if (!mounted) return;

    setState(() {
      if (task != null) _task = task;
      _events = events;
      final created = (_task['createdAt'] as num?)?.toInt();
      if (created != null && created > 0) {
        _startedAt = DateTime.fromMillisecondsSinceEpoch(created);
      }
    });

    final result = (_task['result'] as String?) ?? '';
    if (result.isNotEmpty && result != _revealedFrom) _startReveal(result);

    // Stop polling once nothing can change again.
    if (!_isLive && _revealed >= _tokens.length) {
      _poll?.cancel();
      _poll = null;
    }
  }

  bool get _isLive => _live.contains(_task['status'] as String? ?? 'QUEUED');

  /// Earlier answers and replies already in the event log, so a follow-up
  /// does not erase the first turn.
  List<Widget> _priorTurns() {
    final out = <Widget>[];
    final current = (_task['result'] as String?) ?? '';
    final answers = _events.where((e) => e['type'] == 'agent.answer').toList();
    for (final e in _events) {
      final type = e['type'] as String? ?? '';
      final detail = e['detail'] as String? ?? '';
      if (detail.isEmpty) continue;
      if (type == 'user.followup') {
        out.add(UserTurn(text: detail, stamp: _stamp(e['at'])));
        out.add(const SizedBox(height: AgentMetrics.turnGap));
      } else if (type == 'agent.answer') {
        final isLast = answers.isNotEmpty && identical(e, answers.last);
        if (isLast && detail == current) continue;
        out.add(AgentTurn(
          child: AnswerView(document: AnswerDocument.parse(detail)),
        ));
        out.add(const SizedBox(height: AgentMetrics.turnGap));
      }
    }
    return out;
  }

  /// WAITING is live (keep polling) but not working (no spinner).
  bool get _isWorking {
    final s = _task['status'] as String? ?? 'QUEUED';
    return s == 'QUEUED' || s == 'RUNNING' || s == 'VERIFYING';
  }

  /// Reveal the final answer word by word.
  ///
  /// Two paths converge here. When a remote provider streams, the raw answer
  /// already arrived through the EventChannel (see [_onStreamEvent]); this
  /// then reveals only what the verified result adds — the figure check, the
  /// read time, a recurrence notice — so the reader never watches the same
  /// words twice. Without a stream (the local provider, or a task reopened
  /// later) this is the timed reveal of the whole answer, and a restored
  /// answer paints at full opacity rather than re-animating.
  void _startReveal(String result) {
    _reveal?.cancel();
    _revealedFrom = result;

    // A live stream already showed the raw answer word by word; re-running
    // the whole thing would replay text the reader just watched arrive. Reveal
    // only what the final result adds and leave the streamed prefix in place.
    if (_streamBuf.isNotEmpty && result.startsWith(_streamBuf)) {
      final tail = _tokenise(result.substring(_streamBuf.length));
      setState(() {
        _tokens = [..._tokens, ...tail];
        _streamBuf = '';
        _streaming = false;
      });
      if (tail.isEmpty) {
        setState(() => _revealed = _tokens.length);
        return;
      }
      _revealRemaining();
      return;
    }

    _tokens = _tokenise(result);
    _streamBuf = '';

    // A task restored from a previous session should not re-animate; only
    // reveal progressively when the answer landed while we were watching.
    final animate = _isLive || _revealed > 0;
    if (!animate) {
      setState(() => _revealed = _tokens.length);
      return;
    }

    setState(() => _revealed = 0);
    _revealRemaining();
  }

  /// Reveal the not-yet-shown tail of [_tokens], one word per tick.
  void _revealRemaining() {
    _reveal = Timer.periodic(const Duration(milliseconds: 55), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      if (_revealed >= _tokens.length) {
        t.cancel();
        return;
      }
      setState(() => _revealed++);
      _autoScroll();
    });
  }

  /// Split an answer into words, turning markup and bare URLs into tokens.
  List<StreamToken> _tokenise(String text) =>
      AnswerDocument.parse(text).toStreamTokens();

  AnswerDocument get _document {
    final text = _revealedFrom.isNotEmpty
        ? _revealedFrom
        : (_streamBuf.isNotEmpty ? _streamBuf : '');
    return AnswerDocument.parse(text);
  }

  List<EvidenceCardData> get _cards {
    final raw = (_task['artifacts'] as String?) ?? '';
    return EvidenceCardData.pick(
      instruction: widget.instruction ??
          _task['instruction'] as String? ??
          widget.title,
      answer: (_task['result'] as String?) ?? '',
      artifacts: EvidenceCardData.fromArtifacts(raw),
    );
  }

  String get _pendingKind {
    final tool = (_task['pendingTool'] as String?) ?? '';
    if (tool.isNotEmpty) return tool;
    final error = (_task['error'] as String?) ?? '';
    final lower = error.toLowerCase();
    if (lower.contains('visible tab') || lower.contains('file upload')) {
      return 'upload';
    }
    if (lower.contains('signed-in') || lower.contains('grant the site')) {
      return 'grant';
    }
    if (lower.contains('connection') || lower.contains('offline')) {
      return 'network';
    }
    return '';
  }

  String? get _pendingUrl {
    final error = (_task['error'] as String?) ?? '';
    final m = RegExp(r'https?://[^\s]+').firstMatch(error);
    return m?.group(0);
  }

  void _autoScroll() {
    if (!_scroll.hasClients) return;
    final max = _scroll.position.maxScrollExtent;
    // Only follow if the reader is already near the bottom; yanking them back
    // while they are reading earlier text is worse than not following.
    if (max - _scroll.offset < 220) {
      _scroll.jumpTo(max);
    }
  }

  // ----------------------------------------------------------------- trace

  /// The pipeline trace, built from the event log rather than a fixed plan.
  List<TraceStep> get _steps {
    final steps = <TraceStep>[];
    final calls = <String, int>{};

    for (final e in _events) {
      final type = e['type'] as String? ?? '';
      final detail = e['detail'] as String? ?? '';
      switch (type) {
        case 'tool.call':
          calls[detail] = steps.length;
          steps.add(TraceStep(
            label: _verb(detail),
            chip: _argument(detail),
            mono: _looksMachine(detail),
            running: true,
          ));
          break;
        case 'tool.result':
        case 'tool.denied':
          // Close the most recent open step of this tool.
          final i = steps.lastIndexWhere((s) => s.running);
          if (i >= 0) {
            steps[i] = TraceStep(
              label: steps[i].label,
              chip: steps[i].chip,
              mono: steps[i].mono,
              duration: _durationIn(detail),
              detail: detail.isEmpty ? const [] : [detail],
              detailMono: true,
              denied: type == 'tool.denied',
            );
          }
          break;
        default:
          break;
      }
    }
    return steps;
  }

  /// `browser open https://…` → `Open`.
  String _verb(String detail) {
    final parts = detail.trim().split(RegExp(r'\s+'));
    if (parts.length < 2) return parts.isEmpty ? 'Step' : _title(parts.first);
    return _title(parts[1]);
  }

  /// The argument the verb acted on — a URL, a query, a filename.
  String? _argument(String detail) {
    final parts = detail.trim().split(RegExp(r'\s+'));
    if (parts.length < 3) return null;
    final rest = parts.sublist(2).join(' ');
    final m = RegExp(r'https?://([^/\s]+)').firstMatch(rest);
    return m != null ? m.group(1)!.replaceFirst(RegExp(r'^www\.'), '') : rest;
  }

  bool _looksMachine(String detail) =>
      detail.contains('http') || detail.contains('.');

  String? _durationIn(String detail) {
    final m = RegExp(r'in (\d+)ms').firstMatch(detail);
    if (m == null) return null;
    final ms = int.parse(m.group(1)!);
    return ms < 1000 ? '${ms}ms' : '${(ms / 1000).toStringAsFixed(1)}s';
  }

  static String _title(String s) =>
      s.isEmpty ? s : s[0].toUpperCase() + s.substring(1).toLowerCase();

  /// `Thought for 4 seconds`, from the first and last event.
  String get _doneLabel {
    if (_events.length < 2) return 'Thought for a moment';
    final first = (_events.first['at'] as num?)?.toInt() ?? 0;
    final last = (_events.last['at'] as num?)?.toInt() ?? 0;
    final s = (last - first) / 1000.0;
    if (s <= 0) return 'Thought for a moment';
    if (s < 1) return 'Thought for under a second';
    if (s < 60) {
      final n = s.round();
      return 'Thought for $n second${n == 1 ? "" : "s"}';
    }
    return 'Thought for ${(s / 60).toStringAsFixed(1)} minutes';
  }

  /// What the agent is doing right now, for the shimmer label.
  String get _activeLabel {
    final step = _task['step'] as String? ?? '';
    if (step.isNotEmpty) return step;
    for (final e in _events.reversed) {
      if (e['type'] == 'tool.call') {
        final d = e['detail'] as String? ?? '';
        final arg = _argument(d);
        return arg == null ? _verb(d) : '${_verb(d)} $arg';
      }
    }
    return 'Thinking';
  }

  /// Sources are the pages the agent actually opened.
  List<AgentSource> get _sources {
    final seen = <String, AgentSource>{};
    for (final e in _events) {
      if (e['type'] != 'tool.call') continue;
      final m = RegExp(r'https?://([^/\s]+)')
          .firstMatch(e['detail'] as String? ?? '');
      if (m == null) continue;
      final domain = m.group(1)!.replaceFirst(RegExp(r'^www\.'), '');
      seen.putIfAbsent(
          domain,
          () => AgentSource(
              title: domain, domain: domain, url: m.group(0)!));
    }
    return seen.values.toList();
  }

  // --------------------------------------------------------------- actions

  /// Follow-ups that mean "re-run what you were just doing", not a new question.
  static final _recheckPhrases = {
    'again', 'check again', 'check now', 'recheck', 're-check', 're check',
    'update', 'refresh', 'any change', 'any update', 'what now', 'run again',
    'once more', 'once again', 'any news', 'what about now',
  };

  bool _isRecheck(String text) {
    final t = text.toLowerCase().trim();
    return _recheckPhrases.contains(t);
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    _input.clear();

    // A reply in this composer belongs to THIS task. Spawning a new chat
    // was the bug: "also download it from nkiri.ink" forked a thread and
    // threw away the conversation. Recheck phrases re-run the original
    // ask; everything else is a follow-up on the same id.
    if (widget.taskId != null) {
      final ok = await NativeBridge.guard(
        () => _isRecheck(text)
            ? NativeBridge.rerunTask(widget.taskId!)
            : NativeBridge.followUpTask(widget.taskId!, text),
        false,
        'could not continue that',
      );
      if (!mounted) return;
      if (ok) {
        setState(() {
          _revealedFrom = '';
          _streamBuf = '';
          _tokens = const [];
          _revealed = 0;
        });
        _poll?.cancel();
        _poll = Timer.periodic(const Duration(milliseconds: 600), (_) => _refresh());
        _refresh();
        return;
      }
      AppToast.show(context, 'Could not continue this task');
      return;
    }

    final started = await NativeBridge.guard(
      () => NativeBridge.runTask(text),
      const <String, dynamic>{},
      'could not start that',
    );
    if (!mounted) return;
    final id = (started['id'] as num?)?.toInt();
    if (id == null) {
      AppToast.show(context, 'Could not start that');
      return;
    }
    Navigator.of(context).pushReplacement(MaterialPageRoute(
      builder: (_) => TaskChatScreen(
          taskId: id, title: text, instruction: text),
    ));
  }

  Future<void> _resolve(bool allow) async {
    final id = widget.taskId;
    if (id == null) return;
    final ok = await NativeBridge.guard(
      () => NativeBridge.resolveApproval(id, allow: allow),
      false,
      'could not resolve approval',
    );
    if (!mounted) return;
    AppToast.show(context, ok ? (allow ? 'Allowed — running…' : 'Declined') : 'Could not update that');
    if (allow) {
      setState(() {
        _revealedFrom = '';
        _streamBuf = '';
        _tokens = const [];
        _revealed = 0;
      });
    }
    _refresh();
  }

  Future<void> _stop() async {
    final id = widget.taskId;
    if (id == null) return;
    final ok = await NativeBridge.guard(
      () => NativeBridge.cancelTask(id),
      false,
      'could not stop this task',
    );
    if (!mounted) return;
    AppToast.show(context, ok ? 'Stopping…' : 'Could not stop this task');
    _refresh();
  }

  void _copy() {
    final result = _task['result'] as String? ?? '';
    if (result.isEmpty) return;
    final plain = AnswerDocument.parse(result).plainText;
    Clipboard.setData(ClipboardData(text: plain.isEmpty ? result : plain));
    AppToast.show(context, 'Copied');
  }

  /// Open the parked page in a visible tab so the user can finish a file
  /// upload or sign-in. The chat stays underneath.
  void _openPending() {
    final url = _pendingUrl;
    if (url == null || url.isEmpty) return;
    if (widget.onOpenUrl != null) {
      widget.onOpenUrl!(url);
      return;
    }
    AppToast.show(context, url);
  }

  // ----------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    final instruction =
        widget.instruction ?? _task['instruction'] as String? ?? widget.title;
    final result = _task['result'] as String? ?? '';
    final error = _task['error'] as String? ?? '';
    final status = _task['status'] as String? ?? '';
    final steps = _steps;
    // The caret blinks while a live stream is still generating, not only
    // while the timed reveal is catching up to a finished answer.
    final streaming = _streaming || _revealed < _tokens.length;

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        child: Column(
          children: [
            _topBar(instruction),
            Expanded(
              child: ListView(
                controller: _scroll,
                padding: const EdgeInsets.only(top: 4, bottom: 18),
                children: [
                  UserTurn(text: instruction, stamp: _stamp(_task['createdAt'])),
                  const SizedBox(height: AgentMetrics.turnGap),
                  ..._priorTurns(),

                  AgentTurn(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Working: loader on top, trace filling beneath it.
                        if (_isWorking) ...[
                          AgentWorkingLine(
                              label: _activeLabel, since: _startedAt),
                          if (steps.isNotEmpty) const SizedBox(height: 11),
                        ],
                        if (steps.isNotEmpty)
                          AgentTrace(
                            steps: steps,
                            running: _isWorking,
                            activeLabel: 'Thinking',
                            doneLabel: _doneLabel,
                          ),
                        if (status == 'CANCELLED' && result.isEmpty) ...[
                          const SizedBox(height: 6),
                          Text('Stopped',
                              style: AppTheme.sans(
                                  size: 12.5,
                                  color: AppColors.textMuted,
                                  w: FontWeight.w600)),
                        ],
                        if (_tokens.isNotEmpty || _document.blocks.isNotEmpty) ...[
                          const SizedBox(height: 7),
                          AnswerView(
                            document: _document.isEmpty
                                ? AnswerDocument.parse(_revealedFrom)
                                : _document,
                            cards: result.isNotEmpty && !streaming
                                ? _cards
                                : const [],
                            sources: _sources,
                            visible: _revealed,
                            caret: streaming,
                            onSourceTap: (s) {
                              if (s.url.isNotEmpty) AppToast.show(context, s.url);
                            },
                            onCardTap: (c) {
                              if (c.url.isEmpty) return;
                              if (widget.onOpenUrl != null) {
                                widget.onOpenUrl!(c.url);
                              } else {
                                AppToast.show(context, c.url);
                              }
                            },
                          ),
                        ],
                        if (error.isNotEmpty && status != 'WAITING') ...[
                          const SizedBox(height: 8),
                          Text(error,
                              style: AppTheme.sans(
                                  size: 12.5,
                                  color: AppColors.textDim,
                                  height: 1.55)),
                        ],
                        if (status == 'WAITING') ...[
                          const SizedBox(height: 12),
                          WaitingPrompt(
                            kind: _pendingKind,
                            message: error,
                            url: _pendingUrl,
                            onAllow: () => _resolve(true),
                            onDeny: () => _resolve(false),
                            onOpen: widget.onOpenUrl == null && _pendingUrl == null
                                ? null
                                : _openPending,
                          ),
                        ],
                        // The tail appears only once the answer has settled,
                        // so controls never move under a reader's thumb.
                        if (result.isNotEmpty && !streaming) ...[
                          const SizedBox(height: 11),
                          const Divider(
                              height: 1, thickness: 1, color: AppColors.line),
                          const SizedBox(height: 8),
                          AgentActions(
                            sources: _sources,
                            onCopy: _copy,
                            onSourceTap: (s) => AppToast.show(context, s.url),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (result.isNotEmpty && !streaming)
                    AgentStamp(_stamp(_task['updatedAt'])),
                ],
              ),
            ),
            _composer(),
          ],
        ),
      ),
    );
  }

  Widget _topBar(String instruction) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          AgentMetrics.gutter, 6, AgentMetrics.gutter, 10),
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => Navigator.of(context).pop(),
            child: Container(
              width: 31,
              height: 31,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.line),
              ),
              child: const Icon(Icons.chevron_left,
                  size: 17, color: AppColors.textDim),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              instruction,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTheme.sans(size: 13.5, w: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  Widget _composer() {
    return Container(
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      padding: const EdgeInsets.fromLTRB(12, 9, 12, 9),
      child: Row(
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(21),
                border: Border.all(color: AppColors.lineStrong),
              ),
              padding: const EdgeInsets.only(left: 14, right: 7),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      onSubmitted: (_) => _send(),
                      textInputAction: TextInputAction.send,
                      style: AppTheme.sans(size: 12.5),
                      cursorColor: AppColors.accent,
                      decoration: InputDecoration(
                        isDense: true,
                        border: InputBorder.none,
                        contentPadding: const EdgeInsets.symmetric(vertical: 12),
                        hintText: 'Reply to this task…',
                        hintStyle: AppTheme.sans(
                            size: 12.5, color: AppColors.textMuted),
                      ),
                    ),
                  ),
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: _isLive ? _stop : _send,
                    child: Container(
                      width: 29,
                      height: 29,
                      margin: const EdgeInsets.symmetric(vertical: 7),
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color:
                            _isLive ? AppColors.surface3 : AppColors.accent,
                      ),
                      child: Icon(
                        _isLive ? Icons.stop : Icons.arrow_upward,
                        size: 15,
                        color: _isLive
                            ? AppColors.textDim
                            : AppColors.accentInk,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _stamp(Object? millis) {
    final ms = (millis as num?)?.toInt();
    if (ms == null || ms == 0) return '';
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.hour.toString().padLeft(2, "0")}:'
        '${d.minute.toString().padLeft(2, "0")}';
  }
}
