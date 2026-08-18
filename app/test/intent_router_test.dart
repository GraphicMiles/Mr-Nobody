import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/router/intent_router.dart';

/// The address-bar classifier. A question or vague fact lookup must go to the
/// agent, not a DuckDuckGo results page — that was the bug where "what is
/// hrithik roshan age" opened a search instead of being answered.
void main() {
  test('URLs route to the browser', () {
    expect(IntentRouter.route('https://example.com/page'), IntentType.url);
    expect(IntentRouter.route('example.com'), IntentType.url);
    expect(IntentRouter.route('192.168.1.1'), IntentType.url);
  });

  test('task verbs route to the agent', () {
    expect(IntentRouter.route('find laptops under 500000'), IntentType.task);
    expect(IntentRouter.route('download the report'), IntentType.task);
    expect(IntentRouter.route('track the bitcoin price'), IntentType.task);
  });

  test('natural-language questions route to the agent', () {
    expect(IntentRouter.route('what is hrithik roshan age'), IntentType.task);
    expect(IntentRouter.route('how old is hrithik roshan'), IntentType.task);
    expect(IntentRouter.route('who is the president'), IntentType.task);
    expect(IntentRouter.route('what is the capital of ghana'), IntentType.task);
  });

  test('vague or partial fact lookups route to the agent', () {
    expect(IntentRouter.route('hrithik roshan age'), IntentType.task);
    expect(IntentRouter.route('bitcoin price'), IntentType.task);
  });

  test('plain phrases still search', () {
    expect(IntentRouter.route('arsenal'), IntentType.search);
    expect(IntentRouter.route('latest arsenal result'), IntentType.search);
  });
}
