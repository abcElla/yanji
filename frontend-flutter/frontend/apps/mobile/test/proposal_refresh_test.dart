import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  testWidgets(
      'approval refreshes a report detail already cached before the run',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var applied = false;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path.endsWith('/approve')) applied = true;
      final data = switch (r.path) {
        '/weekly-reports/report-1' => {
            'id': 'report-1',
            'version': applied ? 2 : 1,
            'done_items': applied
                ? [
                    {'text': '已确认实验'}
                  ]
                : [],
          },
        '/agent-runs/run-1' => {'id': 'run-1', 'status': 'succeeded'},
        '/agent-runs/run-1/events' => {'events': [], 'next_seq': 0},
        '/proposals' => {
            'proposals': [
              {
                'id': 'proposal-1',
                'version': applied ? 2 : 1,
                'status': applied ? 'applied' : 'pending',
                'action_type': 'weekly_report.draft',
                'payload': {'week_start': '2026-10-05'},
                'source_refs': [],
              }
            ]
          },
        '/proposals/proposal-1/approve' => {'status': 'applied'},
        '/proposals/proposal-1' => {'can_approve': true},
        _ => throw StateError(r.path),
      };
      resolveData(r, h, data);
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final initial = await tester
        .runAsync(() => container.read(reportProvider('report-1').future));
    expect(initial!['version'], 1);
    await mountPage(tester, container, const AgentRunScreen(runId: 'run-1'));
    await tapText(tester, '确认应用');
    expect(applied, isTrue);
    final refreshed = await tester
        .runAsync(() => container.read(reportProvider('report-1').future));
    expect(refreshed!['version'], 2);
    expect(refreshed['done_items'], isNotEmpty);
  });
}
