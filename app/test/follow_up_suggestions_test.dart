import 'package:flutter_test/flutter_test.dart';
import 'package:mrnobody/agent/follow_up_suggestions.dart';

void main() {
  test('grounded research suggestions use the current turn topic', () {
    final items = FollowUpSuggestions.build(
      instruction: 'who created bitcoin',
      answer: 'Satoshi Nakamoto created Bitcoin.',
      sourceCount: 3,
      activityKinds: const ['search', 'http.fetch', 'answer'],
    );

    expect(items, contains('Explain who created bitcoin more simply'));
    expect(items, contains('Compare the sources on who created bitcoin'));
    expect(items.join(' '), isNot(contains('sky')));
  });

  test('instruction directives are not copied into suggestions', () {
    final items = FollowUpSuggestions.build(
      instruction:
          'Research why the sky appears blue. Use at least two reliable sources and include citations.',
      answer: 'Rayleigh scattering.',
      sourceCount: 2,
      activityKinds: const ['search'],
    );

    expect(items.first, 'Explain why the sky appears blue more simply');
    expect(items.join(' '), isNot(contains('include citations')));
  });

  test('task verbs are removed from the suggested topic', () {
    final items = FollowUpSuggestions.build(
      instruction: 'Find laptops under 500000',
      answer: 'A grounded result.',
      sourceCount: 1,
      activityKinds: const ['http.fetch'],
    );
    expect(items.first, 'Explain laptops under 500000 more simply');
  });

  test('download suggestions differ from research suggestions', () {
    final items = FollowUpSuggestions.build(
      instruction: 'download the file',
      answer: 'Downloaded file.zip.',
      sourceCount: 0,
      activityKinds: const ['download'],
    );

    expect(items, ['Where was the file saved?', 'Check the download again']);
  });

  test('an ungrounded direct reply does not grow a suggestion section', () {
    final items = FollowUpSuggestions.build(
      instruction: 'thanks',
      answer: "You're welcome.",
      sourceCount: 0,
      activityKinds: const ['answer'],
    );
    expect(items, isEmpty);
  });
}
