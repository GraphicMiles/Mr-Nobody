/// Builds a small set of actions from the response that actually exists.
///
/// Suggestions are conditional response data, not a fixed section: research
/// with multiple read sources can offer comparison; a download can offer file
/// follow-up; a conversational reply or failed task offers nothing.
abstract final class FollowUpSuggestions {
  static List<String> build({
    required String instruction,
    required String answer,
    required int sourceCount,
    required Iterable<String> activityKinds,
  }) {
    if (answer.trim().isEmpty) return const [];
    final kinds = activityKinds.toSet();

    if (kinds.any((kind) => kind == 'download' || kind.startsWith('download.'))) {
      return const [
        'Where was the file saved?',
        'Check the download again',
      ];
    }

    if (sourceCount <= 0) return const [];
    final topic = _topic(instruction);
    if (topic.isEmpty) return const [];

    final out = <String>['Explain $topic more simply'];
    if (sourceCount > 1) {
      out.add('Compare the sources on $topic');
    } else {
      out.add('Find another source about $topic');
    }
    return out;
  }

  static String _topic(String instruction) {
    var text = instruction.trim().replaceAll(RegExp(r'\s+'), ' ');
    text = text.replaceFirst(
      RegExp(r'^(research|explain|tell me about|find out|find|look up)\s+',
          caseSensitive: false),
      '',
    );
    final directive = RegExp(
      r'(?:[.!?]\s+|\s+(?:use at least|include citations|and include|with citations|cite sources).*)',
      caseSensitive: false,
    ).firstMatch(text);
    if (directive != null) text = text.substring(0, directive.start);
    text = text.replaceAll(RegExp(r'[.?!,:;]+$'), '').trim();
    if (text.length > 72) text = '${text.substring(0, 71).trimRight()}…';
    return text;
  }
}
