import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  for (final proposalOnly in [false, true]) {
    testWidgets(
        'unreadable run evidence never renders excerpts or proposals ($proposalOnly)',
        (tester) async {
      final api = ApiClient('http://localhost');
      final sourceRef = {
        'source_id': 'private-source',
        'source_revision': 1,
        'locator': {'kind': 'pdf_page', 'page': 1},
        'quote': '私有材料秘密'
      };
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.path == '/sources/private-source') {
          rejectData(r, h, 404, '对象不存在');
        } else if (r.path == '/agent-runs/private-run') {
          resolveData(r, h, {
            'id': 'private-run',
            'status': 'succeeded',
            'result': {
              'summary': '包含私有信息的摘要',
              'claims': proposalOnly
                  ? []
                  : [
                      {
                        'text': '私有材料秘密',
                        'source_refs': [sourceRef]
                      }
                    ]
            }
          });
        } else if (r.path.endsWith('/events')) {
          resolveData(r, h, {'events': [], 'next_seq': 0});
        } else {
          resolveData(r, h, {
            'proposals': proposalOnly
                ? [
                    {
                      'id': 'proposal',
                      'status': 'applied',
                      'reason': '私有草稿秘密',
                      'source_refs': [sourceRef]
                    }
                  ]
                : []
          });
        }
      }));
      final container = testContainer(api, mentor: true);
      addTearDown(container.dispose);
      await mountPage(
          tester, container, const AgentRunScreen(runId: 'private-run'));
      expect(find.textContaining('私有材料秘密'), findsNothing);
      expect(find.textContaining('私有草稿秘密'), findsNothing);
      expect(find.text('包含私有信息的摘要'), findsNothing);
      expect(find.textContaining('无权查看'), findsOneWidget);
    });
  }
}
