import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/answer_document.dart';
import 'package:mrnobody/widgets/answer_view.dart';

void main() {
  Widget host(Widget child) => MaterialApp(
        theme: AppTheme.dark(),
        home: Scaffold(
          backgroundColor: AppColors.bg,
          body: SizedBox(width: 360, child: child),
        ),
      );

  group('parse', () {
    test('a heading, bold names and a list become structure', () {
      const raw = '''
# Latest Prime Video series

**The Boys** season 5 lands in 2026. **Fallout** season 2 follows.

- The Boys
- Fallout
- Reacher
''';
      final doc = AnswerDocument.parse(raw);
      expect(doc.blocks.first, isA<AnswerHeading>());
      expect(doc.plainText, isNot(contains('**')));
      expect(doc.plainText, contains('The Boys'));
      expect(doc.blocks.whereType<AnswerList>().length, 1);
      expect(doc.isPlain, isFalse);
    });

    test('literal asterisks never survive a finished pair', () {
      final doc = AnswerDocument.parse(
          'Watch **Fallout** and **The Boys** on Prime.');
      expect(doc.plainText, 'Watch Fallout and The Boys on Prime.');
      final bold = doc.blocks.first.spans.where((s) => s.bold).map((s) => s.text);
      expect(bold, contains('Fallout'));
      expect(bold, contains('The Boys'));
    });

    test('an unpaired ** at the end of a stream is hidden, not printed', () {
      final doc = AnswerDocument.parse('Watch **Fallout');
      expect(doc.plainText, isNot(contains('*')));
      expect(doc.plainText, contains('Fallout'));
    });

    test('a one-line answer with a URL stays plain for the golden path', () {
      final doc = AnswerDocument.parse(
          'I found current laptop listings under 500000 at https://example.com/laptops');
      expect(doc.isPlain, isTrue);
      final tokens = doc.toStreamTokens();
      expect(tokens.any((t) => t.cite?.domain == 'example.com'), isTrue);
    });

    test('code fences are unwrapped rather than shown', () {
      final doc = AnswerDocument.parse('```\n# Title\nHello **world**\n```');
      expect(doc.blocks.first, isA<AnswerHeading>());
      expect(doc.plainText, isNot(contains('```')));
    });
  });

  group('evidence cards', () {
    test('picks two or three artifacts for a visual query', () {
      final cards = EvidenceCardData.pick(
        instruction: 'find me the latest Prime web series',
        answer: '# Latest Prime series',
        artifacts: const [
          EvidenceCardData(
              title: 'The Boys', domain: 'primevideo.com', url: 'https://a'),
          EvidenceCardData(
              title: 'Fallout', domain: 'primevideo.com', url: 'https://b'),
          EvidenceCardData(
              title: 'Reacher', domain: 'primevideo.com', url: 'https://c'),
          EvidenceCardData(
              title: 'Extra', domain: 'primevideo.com', url: 'https://d'),
        ],
      );
      expect(cards.length, 3);
      expect(cards.first.title, 'The Boys');
    });

    test('does not invent cards for a one-line figure', () {
      final cards = EvidenceCardData.pick(
        instruction: 'what is 2+2',
        answer: '4',
        artifacts: const [
          EvidenceCardData(title: 'Math', domain: 'x.com', url: 'https://x'),
          EvidenceCardData(title: 'Also', domain: 'y.com', url: 'https://y'),
        ],
      );
      expect(cards, isEmpty);
    });

    test('reads the core shortlist JSON', () {
      const json =
          '[{"n":1,"title":"The Boys","url":"https://www.primevideo.com/boys","note":"s5"}]';
      final cards = EvidenceCardData.fromArtifacts(json);
      expect(cards, hasLength(1));
      expect(cards.first.domain, 'primevideo.com');
    });
  });

  group('renderer', () {
    testWidgets('never paints raw asterisks', (tester) async {
      await tester.pumpWidget(host(
        AnswerView(
          document: AnswerDocument.parse(
              '# Latest Prime series\n\n**The Boys** is the one to watch.'),
        ),
      ));
      await tester.pump();
      expect(find.textContaining('**'), findsNothing);
      expect(find.textContaining('The Boys'), findsWidgets);
      expect(find.textContaining('Latest Prime series'), findsOneWidget);
    });

    testWidgets('a waiting upload offers Open page, not only Allow',
        (tester) async {
      String? opened;
      await tester.pumpWidget(host(
        WaitingPrompt(
          kind: 'upload',
          message:
              'File upload needs a visible tab.\nhttps://example.com/form',
          url: 'https://example.com/form',
          onAllow: () {},
          onDeny: () {},
          onOpen: () => opened = 'https://example.com/form',
        ),
      ));
      await tester.pump();
      expect(find.text('Open page'), findsOneWidget);
      expect(find.text("I've finished"), findsOneWidget);
      expect(find.text('Needs a visible tab'), findsOneWidget);
      await tester.tap(find.text('Open page'));
      expect(opened, 'https://example.com/form');
    });
  });
}
