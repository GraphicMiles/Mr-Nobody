import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../agent/follow_up_suggestions.dart';
import '../agent/task_timeline.dart';
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
  static const _live = {
    'QUEUED',
    'RUNNING',
    'VERIFYING',
    'WAITING',
    'WAITING_EXTERNAL'
  };

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

  Map<String, dynamic>? _downloadState;

  @override
  void initState() {
    super.initState();
    if (widget.taskId != null) {
      _refresh();
      _poll =
          Timer.periodic(const Duration(milliseconds: 600), (_) => _refresh());
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
      case 'downloadProgress':
        setState(() {
          _downloadState = {
            'name': map['text'] as String? ?? 'download',
            'bytes': (map['bytes'] as num?)?.toInt() ?? 0,
            'total': (map['total'] as num?)?.toInt() ?? -1,
            'status': map['status'] as String? ?? 'RUNNING',
          };
        });
        _autoScroll();
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
      int? started;
      for (final event in events.reversed) {
        final type = event['type'] as String? ?? '';
        if (type == 'task.started' || type == 'user.followup') {
          started = (event['at'] as num?)?.toInt();
          if (started != null && started > 0) break;
        }
      }
      started ??= (_task['createdAt'] as num?)?.toInt();
      if (started != null && started > 0) {
        _startedAt = DateTime.fromMillisecondsSinceEpoch(started);
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

  /// Earlier turns retain their own trace, citations, source controls and
  /// optional evidence cards. A new reply must not steal or erase the visual
  /// record of the work that produced the previous answer.
  List<Widget> _priorTurns() {
    final out = <Widget>[];
    final current = (_task['result'] as String?) ?? '';
    final answerIndexes = <int>[
      for (var i = 0; i < _events.length; i++)
        if (_events[i]['type'] == 'agent.answer') i,
    ];
    final lastAnswer = answerIndexes.isEmpty ? -1 : answerIndexes.last;
    var segmentStart = 0;
    var turnInstruction =
        widget.instruction ?? _task['instruction'] as String? ?? widget.title;

    for (var i = 0; i < _events.length; i++) {
      final event = _events[i];
      final type = event['type'] as String? ?? '';
      final detail = event['detail'] as String? ?? '';

      if (type == 'user.followup' && detail.isNotEmpty) {
        turnInstruction = detail;
        segmentStart = i;
        out.add(UserTurn(text: detail, stamp: _stamp(event['at'])));
        out.add(const SizedBox(height: AgentMetrics.turnGap));
        continue;
      }
      if (type == 'task.started') {
        segmentStart = i;
        continue;
      }
      if (type != 'agent.answer' || detail.isEmpty) continue;
      if (i == lastAnswer && detail == current) continue;

      var segmentEnd = _events.length;
      var artifactsRaw = '';
      for (var j = i + 1; j < _events.length; j++) {
        final followingType = _events[j]['type'] as String? ?? '';
        if (followingType == 'user.followup' ||
            followingType == 'task.started') {
          segmentEnd = j;
          break;
        }
        if (followingType == 'turn.presentation') {
          artifactsRaw =
              _presentationArtifacts(_events[j]['detail'] as String? ?? '');
        }
      }

      final segment = _events.sublist(segmentStart, segmentEnd);
      final timeline = TaskTimeline.fromEvents(
        events: segment,
        taskStatus: 'COMPLETED',
      );
      final steps = _stepsFor(timeline);
      final sources = _sourcesFor(timeline, answer: detail);
      final cards = _cardsFrom(
        sources: sources,
        artifactsRaw: artifactsRaw,
        instruction: turnInstruction,
        answer: detail,
      );

      out.add(AgentTurn(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (steps.isNotEmpty) ...[
              AgentTrace(
                steps: steps,
                running: false,
                doneLabel: _doneLabelFor(segment),
              ),
              const SizedBox(height: 7),
            ],
            AnswerView(
              document: AnswerDocument.parse(detail),
              cards: cards,
              sources: sources,
              onSourceTap: (source) {
                if (source.url.isNotEmpty) AppToast.show(context, source.url);
              },
              onCardTap: (card) {
                if (card.url.isEmpty) return;
                if (widget.onOpenUrl != null) {
                  widget.onOpenUrl!(card.url);
                } else {
                  AppToast.show(context, card.url);
                }
              },
            ),
            const SizedBox(height: 11),
            Divider(height: 1, thickness: 1, color: AppColors.line),
            const SizedBox(height: 8),
            AgentActions(
              sources: sources,
              onCopy: () => _copyText(detail),
              onSourceTap: (source) => AppToast.show(context, source.url),
            ),
          ],
        ),
      ));
      out.add(AgentStamp(_stamp(event['at'])));
      out.add(const SizedBox(height: AgentMetrics.turnGap));
    }
    return out;
  }

  static String _presentationArtifacts(String detail) {
    if (!detail.trimLeft().startsWith('{')) return '';
    try {
      final decoded = jsonDecode(detail);
      if (decoded is! Map) return '';
      return decoded['artifacts'] as String? ?? '';
    } catch (_) {
      return '';
    }
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

  /// Research cards require a successful page read. A design preview is a
  /// separate typed artifact produced by the scoped design adapter, not a
  /// search candidate, and may render without a web source.
  List<EvidenceCardData> _cardsFor(List<AgentSource> sources) => _cardsFrom(
        sources: sources,
        artifactsRaw: (_task['artifacts'] as String?) ?? '',
        instruction: _activeTurnInstruction,
        answer: (_task['result'] as String?) ?? '',
      );

  List<EvidenceCardData> _cardsFrom({
    required List<AgentSource> sources,
    required String artifactsRaw,
    required String instruction,
    required String answer,
  }) {
    if (artifactsRaw.isEmpty) return const [];
    final all = EvidenceCardData.fromArtifacts(artifactsRaw);
    final read = sources.map((s) => _normalUrl(s.url)).toSet();
    final artifacts = sources.isEmpty
        ? all.where((a) => a.note == 'design-preview').toList()
        : all.where((a) => read.contains(_normalUrl(a.url))).toList();
    return EvidenceCardData.pick(
      instruction: instruction,
      answer: answer,
      artifacts: artifacts,
    );
  }

  String get _activeTurnInstruction {
    for (final event in _events.reversed) {
      if (event['type'] == 'user.followup') {
        final text = event['detail'] as String? ?? '';
        if (text.isNotEmpty) return text;
      }
    }
    return widget.instruction ??
        _task['instruction'] as String? ??
        widget.title;
  }

  static String _normalUrl(String value) {
    final clean = value.trim().split('#').first;
    return clean.replaceFirst(RegExp(r'/$'), '');
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

  TaskTimeline get _timeline => TaskTimeline.fromEvents(
        events: _events,
        taskStatus: _task['status'] as String? ?? 'QUEUED',
        currentStep: _task['step'] as String? ?? '',
      );

  /// Convert semantic core activities into the stable visual grammar. There
  /// is no fixed list: the timeline contains only activities this run entered.
  List<TraceStep> _stepsFor(TaskTimeline timeline) => [
        for (final activity in timeline.activities)
          TraceStep(
            label: activity.label,
            metric: activity.metric.isEmpty ? null : activity.metric,
            duration: _activityDuration(activity),
            detail: activity.detail,
            running: activity.state == TimelineState.working,
            denied: activity.state == TimelineState.denied,
            recovered: activity.state == TimelineState.recovered,
            failed: activity.state == TimelineState.failed,
            waiting: activity.state == TimelineState.waiting,
            cancelled: activity.state == TimelineState.cancelled,
          ),
      ];

  String? _activityDuration(TimelineActivity activity) {
    if (activity.startedAt <= 0 || activity.endedAt <= activity.startedAt) {
      return null;
    }
    final ms = activity.endedAt - activity.startedAt;
    return ms < 1000 ? '${ms}ms' : '${(ms / 1000).toStringAsFixed(1)}s';
  }

  List<Map<String, dynamic>> get _runEvents {
    var from = 0;
    for (var i = 0; i < _events.length; i++) {
      final type = _events[i]['type'] as String? ?? '';
      if (type == 'task.started' || type == 'user.followup') from = i;
    }
    return _events.sublist(from);
  }

  /// `Thought for 4 seconds`, measured within this execution cycle only.
  String get _doneLabel => _doneLabelFor(_runEvents);

  String _doneLabelFor(List<Map<String, dynamic>> run) {
    if (run.length < 2) return 'Thought for a moment';
    final first = (run.first['at'] as num?)?.toInt() ?? 0;
    final last = (run.last['at'] as num?)?.toInt() ?? 0;
    final s = (last - first) / 1000.0;
    if (s <= 0) return 'Thought for a moment';
    if (s < 1) return 'Thought for under a second';
    if (s < 60) {
      final n = s.round();
      return 'Thought for $n second${n == 1 ? "" : "s"}';
    }
    return 'Thought for ${(s / 60).toStringAsFixed(1)} minutes';
  }

  /// What the agent is doing now comes from its latest semantic activity.
  String _activeLabel(TaskTimeline timeline) {
    if (timeline.activities.isNotEmpty) return timeline.activeLabel;
    final step = _task['step'] as String? ?? '';
    return step.isEmpty ? 'Working' : step;
  }

  /// Citation order is the order in which page content was successfully read,
  /// not search-result order and not every URL a tool happened to mention.
  List<AgentSource> _sourcesFor(TaskTimeline timeline, {String answer = ''}) {
    final read = [
      for (final source in timeline.sources)
        AgentSource(
          title: source.title,
          domain: source.domain,
          url: source.url,
        ),
    ];
    if (answer.isEmpty) return read;

    // Extractive [n] markers refer to successful read order. Do not expose
    // every candidate the agent attempted as if the final answer used it.
    final numbered = <AgentSource>[];
    final seenNumbers = <int>{};
    for (final match in RegExp(r'\[(\d+)\]').allMatches(answer)) {
      final index = int.tryParse(match.group(1) ?? '');
      if (index == null || index < 1 || index > read.length) continue;
      if (seenNumbers.add(index)) numbered.add(read[index - 1]);
    }
    if (numbered.isNotEmpty) return numbered;

    // Some specialised answers cite their selected result as a direct URL.
    final linked = <AgentSource>[];
    final seenUrls = <String>{};
    for (final match in RegExp(r'https?://[^\s<>]+').allMatches(answer)) {
      var url = match.group(0) ?? '';
      url = url.replaceFirst(RegExp(r'[.,);:]+$'), '');
      if (url.isEmpty || !seenUrls.add(url)) continue;
      final host =
          Uri.tryParse(url)?.host.replaceFirst(RegExp(r'^www\.'), '') ?? '';
      if (host.isEmpty) continue;
      linked.add(AgentSource(title: host, domain: host, url: url));
    }
    return linked.isNotEmpty ? linked : read;
  }

  // --------------------------------------------------------------- actions

  /// Follow-ups that mean "re-run what you were just doing", not a new question.
  static final _recheckPhrases = {
    'again',
    'check again',
    'check now',
    'recheck',
    're-check',
    're check',
    'update',
    'refresh',
    'any change',
    'any update',
    'what now',
    'run again',
    'once more',
    'once again',
    'any news',
    'what about now',
  };

  bool _isRecheck(String text) {
    final t = text.toLowerCase().trim();
    return _recheckPhrases.contains(t);
  }

  Future<void> _send() async {
    final text = _input.text;
    _input.clear();
    await _sendText(text);
  }

  Future<void> _sendText(String value) async {
    final text = value.trim();
    if (text.isEmpty || _isLive) return;

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
        _poll = Timer.periodic(
            const Duration(milliseconds: 600), (_) => _refresh());
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
      builder: (_) =>
          TaskChatScreen(taskId: id, title: text, instruction: text),
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
    AppToast.show(
        context,
        ok
            ? (allow ? 'Allowed — running…' : 'Declined')
            : 'Could not update that');
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
    _copyText(_task['result'] as String? ?? '');
  }

  void _copyText(String text) {
    if (text.isEmpty) return;
    final plain = AnswerDocument.parse(text).plainText;
    Clipboard.setData(ClipboardData(text: plain.isEmpty ? text : plain));
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
    final timeline = _timeline;
    final steps = _stepsFor(timeline);
    final sources = _sourcesFor(timeline, answer: result);
    final cards = _cardsFor(sources);
    final followUps = FollowUpSuggestions.build(
      instruction: _activeTurnInstruction,
      answer: result,
      sourceCount: sources.length,
      activityKinds: timeline.activities.map((activity) => activity.kind),
    );
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
                  UserTurn(
                      text: instruction, stamp: _stamp(_task['createdAt'])),
                  const SizedBox(height: AgentMetrics.turnGap),
                  ..._priorTurns(),
                  AgentTurn(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Working: loader on top, trace filling beneath it.
                        if (_downloadState != null) ...[
                          _downloadProgressView(_downloadState!),
                          const SizedBox(height: 8),
                        ],
                        if (_isWorking) ...[
                          AgentWorkingLine(
                              label: _activeLabel(timeline), since: _startedAt),
                          if (steps.isNotEmpty) const SizedBox(height: 11),
                        ],
                        if (steps.isNotEmpty)
                          AgentTrace(
                            steps: steps,
                            running: _isWorking,
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
                        if (_tokens.isNotEmpty ||
                            _document.blocks.isNotEmpty) ...[
                          const SizedBox(height: 7),
                          AnswerView(
                            document: _document.isEmpty
                                ? AnswerDocument.parse(_revealedFrom)
                                : _document,
                            cards: result.isNotEmpty && !streaming
                                ? cards
                                : const [],
                            sources: sources,
                            visible: _revealed,
                            caret: streaming,
                            onSourceTap: (s) {
                              if (s.url.isNotEmpty) {
                                AppToast.show(context, s.url);
                              }
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
                            onOpen:
                                widget.onOpenUrl == null && _pendingUrl == null
                                    ? null
                                    : _openPending,
                          ),
                        ],
                        // The tail appears only once the answer has settled,
                        // so controls never move under a reader's thumb.
                        if (result.isNotEmpty && !streaming) ...[
                          if (followUps.isNotEmpty)
                            AgentFollowUps(
                              items: followUps,
                              onTap: _sendText,
                            ),
                          const SizedBox(height: 11),
                          Divider(
                              height: 1, thickness: 1, color: AppColors.line),
                          const SizedBox(height: 8),
                          AgentActions(
                            sources: sources,
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

  String _bar(int filled) => '${List.filled(filled, '█').join()}${List.filled(20 - filled, '░').join()}';

  /// Expanded while bytes are moving; completed/failed downloads collapse to
  /// one compact status row so the answer remains the focus of the chat.
  Widget _downloadProgressView(Map<String, dynamic> data) {
    final name = data['name'] as String? ?? 'download';
    final bytes = (data['bytes'] as num?)?.toInt() ?? 0;
    final total = (data['total'] as num?)?.toInt() ?? -1;
    final status = data['status'] as String? ?? 'RUNNING';
    final active = status == 'RUNNING' || status == 'QUEUED';
    final pct = total > 0 ? (bytes / total).clamp(0.0, 1.0) : null;
    final label = total > 0
        ? '${(bytes / 1048576).toStringAsFixed(1)} / ${(total / 1048576).toStringAsFixed(1)} MB'
        : '${(bytes / 1048576).toStringAsFixed(1)} MB';
    return Container(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
      decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(10), border: Border.all(color: AppColors.line)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(active ? Icons.download_outlined : (status == 'COMPLETED' ? Icons.check_circle_outline : Icons.error_outline), size: 14, color: active ? AppColors.accent : AppColors.textMuted),
          const SizedBox(width: 7),
          Expanded(child: Text(active ? 'Downloading $name' : '${status.toLowerCase()} · $name', maxLines: 1, overflow: TextOverflow.ellipsis, style: AppTheme.sans(size: 11.5, color: AppColors.textDim, w: FontWeight.w600))),
          Text(active && pct != null ? '${(pct * 100).round()}%' : label, style: AppTheme.mono(size: 10, color: AppColors.textMuted)),
        ]),
        if (active) ...[
          const SizedBox(height: 6),
          Text(pct == null ? '[${_bar(0)}]  calculating' : '[${_bar((pct * 20).round())}]  $label', style: AppTheme.mono(size: 9.5, color: AppColors.textMuted)),
        ],
      ]),
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
              child:
                  Icon(Icons.chevron_left, size: 17, color: AppColors.textDim),
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
      decoration: BoxDecoration(
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
                        contentPadding:
                            const EdgeInsets.symmetric(vertical: 12),
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
                        color: _isLive ? AppColors.surface3 : AppColors.accent,
                      ),
                      child: Icon(
                        _isLive ? Icons.stop : Icons.arrow_upward,
                        size: 15,
                        color:
                            _isLive ? AppColors.textDim : AppColors.accentInk,
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
