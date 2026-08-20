import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:mrnobody/agent/task_timeline.dart';

Map<String, dynamic> event(int seq, String type, Object detail, int at) => {
      'seq': seq,
      'type': type,
      'detail': detail is String ? detail : jsonEncode(detail),
      'at': at,
    };

void main() {
  test('renders only the semantic activities that actually happened', () {
    final events = <Map<String, dynamic>>[
      event(1, 'task.started', 'local', 1000),
      event(2, 'step.changed', {
        'v': 1,
        'shape': 'activity',
        'label': 'Understanding the request',
        'kind': 'classify',
      }, 1010),
      event(3, 'step.changed', {
        'v': 1,
        'shape': 'activity',
        'label': 'Downloading the file',
        'kind': 'download',
      }, 1050),
      event(4, 'tool.call', {
        'v': 1,
        'shape': 'tool_call',
        'id': 'call-1',
        'tool': 'download',
        'action': 'download',
        'subject': 'https://example.com/file.zip',
        'url': 'https://example.com/file.zip',
      }, 1060),
      event(5, 'tool.result', {
        'v': 1,
        'shape': 'tool_result',
        'id': 'call-1',
        'tool': 'download',
        'action': 'download',
        'state': 'done',
        'durationMs': 840,
        'name': 'file.zip',
      }, 1900),
      event(6, 'task.finished', 'COMPLETED', 1910),
    ];

    final timeline = TaskTimeline.fromEvents(
      events: events,
      taskStatus: 'COMPLETED',
    );

    expect(timeline.activities.map((a) => a.label), [
      'Understanding the request',
      'Downloading the file',
    ]);
    expect(timeline.activities.map((a) => a.label), isNot(contains('Searching broadly')));
    expect(timeline.activities.last.metric, 'file.zip');
    expect(timeline.activities.last.state, TimelineState.done);
    expect(timeline.sources, isEmpty);
  });

  test('a failed read followed by browser success is recovered', () {
    final events = <Map<String, dynamic>>[
      event(1, 'step.changed', {
        'label': 'Reading source pages',
        'kind': 'read',
        'reason': 'Read a source.',
      }, 1000),
      event(2, 'tool.call', {
        'id': 'http-1',
        'tool': 'http',
        'action': 'fetch',
        'url': 'https://example.com/story',
        'subject': 'https://example.com/story',
      }, 1010),
      event(3, 'tool.result', {
        'id': 'http-1',
        'state': 'failed',
        'durationMs': 200,
        'reason': 'JavaScript required',
      }, 1210),
      event(4, 'tool.call', {
        'id': 'browser-1',
        'tool': 'browser',
        'action': 'fetch',
        'url': 'https://example.com/story',
        'subject': 'https://example.com/story',
      }, 1220),
      event(5, 'tool.result', {
        'id': 'browser-1',
        'state': 'done',
        'durationMs': 500,
        'url': 'https://example.com/story',
      }, 1720),
    ];

    final timeline = TaskTimeline.fromEvents(
      events: events,
      taskStatus: 'COMPLETED',
    );

    expect(timeline.activities, hasLength(1));
    expect(timeline.activities.single.state, TimelineState.recovered);
    expect(timeline.activities.single.metric, 'example.com');
    expect(timeline.sources, hasLength(1));
    expect(timeline.sources.single.domain, 'example.com');
  });

  test('legacy tool summaries remain adaptive', () {
    final timeline = TaskTimeline.fromEvents(
      events: [
        event(1, 'tool.call', 'search(q=weather Lagos)', 1000),
        event(2, 'tool.result', 'search ok in 80ms', 1080),
        event(3, 'tool.call',
            'http.fetch(url=https://weather.example/lagos)', 1100),
        event(4, 'tool.result', 'http ok in 120ms', 1220),
      ],
      taskStatus: 'COMPLETED',
    );

    expect(timeline.activities.map((a) => a.label), [
      'Searching broadly',
      'Reading source pages',
    ]);
    expect(timeline.activities.first.metric, 'weather Lagos');
    expect(timeline.sources.single.domain, 'weather.example');
  });

  test('only the newest execution cycle appears in the pipeline', () {
    final timeline = TaskTimeline.fromEvents(
      events: [
        event(1, 'task.started', 'local', 1000),
        event(2, 'step.changed', {'label': 'Searching broadly'}, 1010),
        event(3, 'task.finished', 'COMPLETED', 1100),
        event(4, 'user.followup', 'download it', 2000),
        event(5, 'task.started', 'local', 2010),
        event(6, 'step.changed', {'label': 'Downloading the file'}, 2020),
        event(7, 'task.finished', 'COMPLETED', 2100),
      ],
      taskStatus: 'COMPLETED',
    );

    expect(timeline.activities.map((a) => a.label), ['Downloading the file']);
  });

  test('an attempted page is not a source until its read succeeds', () {
    final timeline = TaskTimeline.fromEvents(
      events: [
        event(1, 'tool.call',
            'http.fetch(url=https://failed.example/page)', 1000),
        event(2, 'tool.result', 'http failed in 20ms — timeout', 1020),
      ],
      taskStatus: 'FAILED',
    );

    expect(timeline.sources, isEmpty);
    expect(timeline.activities.single.state, TimelineState.failed);
  });
}
