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

  test('research, read and use phrasings route to the agent (BUG-7)', () {
    expect(IntentRouter.route('research the tallest buildings in Africa'),
        IntentType.task);
    expect(IntentRouter.route('read example.com/article and summarize it'),
        IntentType.task);
    expect(IntentRouter.route('use google search to find cheap flights'),
        IntentType.task);
    expect(IntentRouter.route('look for the best laptops of 2026'),
        IntentType.task);
  });

  test('slash commands force their type', () {
    expect(IntentRouter.route('/agent latest arsenal result'), IntentType.task);
    expect(IntentRouter.route('/task check the weather'), IntentType.task);
    expect(IntentRouter.route('/download https://example.com/f.pdf'),
        IntentType.task);
    expect(IntentRouter.route('/search what is the weather'), IntentType.search);
    expect(IntentRouter.route('/open example page'), IntentType.url);
  });

  test('slash payload strips the command word', () {
    expect(IntentRouter.payload('/agent find laptops'), 'find laptops');
    expect(IntentRouter.payload('/search what is love'), 'what is love');
    expect(IntentRouter.payload('/open example.com'), 'example.com');
    expect(IntentRouter.payload('/download https://example.com/f.pdf'),
        'download https://example.com/f.pdf');
    expect(IntentRouter.payload('  plain text '), 'plain text');
  });

  test('a bare slash word is not a command', () {
    expect(IntentRouter.slashCommand('/agent'), isNull);
    expect(IntentRouter.slashCommand('/searching habits'), isNull);
  });

  test('search queries use the configured engine', () {
    expect(
        IntentRouter.toUrl('titan',
            searchEngine: 'https://www.google.com/search?q='),
        'https://www.google.com/search?q=titan');
    expect(
        IntentRouter.toUrl('titan mail',
            searchEngine: 'https://www.google.com/search?q='),
        'https://www.google.com/search?q=titan%20mail');
    expect(
        IntentRouter.toUrl('titan', searchEngine: 'https://www.bing.com/search?q='),
        'https://www.bing.com/search?q=titan');
    expect(
        IntentRouter.toUrl('titan',
            searchEngine: 'https://www.startpage.com/sp/search?query='),
        'https://www.startpage.com/sp/search?query=titan');
  });

  test('search falls back to DuckDuckGo only without an engine', () {
    expect(IntentRouter.toUrl('titan'), 'https://duckduckgo.com/?q=titan');
    expect(IntentRouter.toUrl('titan', searchEngine: ''),
        'https://duckduckgo.com/?q=titan');
    expect(IntentRouter.toUrl('titan', searchEngine: '   '),
        'https://duckduckgo.com/?q=titan');
  });

  test('urls and domains are untouched by the engine setting', () {
    const google = 'https://www.google.com/search?q=';
    expect(IntentRouter.toUrl('example.com', searchEngine: google),
        'https://example.com');
    expect(
        IntentRouter.toUrl('https://example.com/a', searchEngine: google),
        'https://example.com/a');
    expect(IntentRouter.toUrl('http://example.com', searchEngine: google),
        'https://example.com');
  });
}
