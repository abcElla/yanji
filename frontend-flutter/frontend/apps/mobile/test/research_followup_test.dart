import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/research_followups.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/design_system.dart';
import 'package:dio/dio.dart';
import 'package:go_router/go_router.dart';

import 'improvement_support.dart';

import 'package:yanji_mobile/src/models.dart';

class FollowupTestSession extends SessionController {
  FollowupTestSession(super.ref) {
    state = const AsyncData(
      Session(
        user: {'id': 'student', 'display_name': '学生'},
        roles: ['student'],
        collaborationEnabled: true,
      ),
    );
  }
}

void main() {
  testWidgets('detail polling waits for slow requests to finish', (
    tester,
  ) async {
    var reads = 0;
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (r, h) async {
          reads++;
          await Future<void>.delayed(const Duration(seconds: 10));
          resolveData(r, h, {
            'id': 'plan',
            'version': 1,
            'state': 'active',
            'run': {'status': 'running'},
          });
        },
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(api),
          sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
          tasksProvider.overrideWith((ref) async => []),
        ],
        child: const MaterialApp(
          home: ResearchFollowupDetailScreen(id: 'plan'),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 9));
    expect(reads, 1);
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(find.text('AI 正在根据当前材料整理计划，请稍后查看。'), findsOneWidget);
    await tester.pump(const Duration(seconds: 6));
    expect(reads, 1);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'selected steps can link an existing task instead of creating it',
    (tester) async {
      tester.view.physicalSize = const Size(900, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = ApiClient('http://localhost');
      Map? submitted;
      final plan = {
        'id': 'plan',
        'version': 1,
        'state': 'active',
        'run': {
          'status': 'succeeded',
          'result': {
            'summary': '继续已有任务',
            'next_steps': [
              {'title': '核对参数'},
              {'title': '暂不安排'},
            ],
          },
        },
      };
      api.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            if (r.method == 'POST') {
              submitted = Map.from(r.data as Map);
              resolveData(r, h, {...plan, 'state': 'applied', 'tasks': []});
            } else {
              resolveData(r, h, plan);
            }
          },
        ),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            apiClientProvider.overrideWithValue(api),
            sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
            tasksProvider.overrideWith(
              (ref) async => [
                const ResearchTask(
                  id: 'existing',
                  title: '已有参数核对任务',
                  type: '其他',
                  status: TaskStatus.todo,
                  dueLabel: '',
                  description: '',
                  version: 3,
                ),
              ],
            ),
          ],
          child: const MaterialApp(
            home: ResearchFollowupDetailScreen(id: 'plan'),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('采用第 2 项'));
      await tester.pump();
      await tester.tap(find.byType(DropdownButtonFormField<String>).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('已有参数核对任务').last);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('确认并加入任务'));
      await tester.tap(find.text('确认并加入任务'));
      await tester.pumpAndSettle();
      expect(submitted?['items'], [
        {'existing_task_id': 'existing', 'expected_version': 3},
      ]);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets('slow plan reads finish before the next polling request starts', (
    tester,
  ) async {
    var requests = 0;
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (r, h) async {
          requests++;
          await Future<void>.delayed(const Duration(seconds: 10));
          h.resolve(
            Response(
              requestOptions: r,
              statusCode: 200,
              data: {
                'code': 0,
                'data': {'items': []},
              },
            ),
          );
        },
      ),
    );
    final container = ProviderContainer(
      overrides: [apiClientProvider.overrideWithValue(api)],
    );
    final subscription = container.listen(
      researchFollowupsForScopeProvider('test|student'),
      (_, __) {},
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 9));
    expect(requests, 1);
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(
      container
          .read(researchFollowupsForScopeProvider('test|student'))
          .hasValue,
      isTrue,
    );
    subscription.close();
    container.dispose();
    await tester.pump(const Duration(milliseconds: 1));
  });
  test('research status text uses plain Chinese', () {
    expect(
      readableResearchText('当前todo，前置blocked，已completed'),
      '当前待开始，前置等待前置任务，已已完成',
    );
  });
  testWidgets(
    'opening the mentor chat shows the latest answer in long history',
    (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final rows = List.generate(
        6,
        (i) => <String, dynamic>{
          'id': 'p$i',
          'state': 'active',
          'changes': [
            {'question': '问题$i'},
          ],
          'run': {
            'status': 'succeeded',
            'result': {
              'summary': i == 0 ? '最新回答' : List.filled(10, '这是较早的研究建议。').join(),
            },
          },
        },
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
            researchFollowupsProvider.overrideWith((ref) => AsyncData(rows)),
          ],
          child: MaterialApp(
            theme: ResearchDesign.theme(),
            home: const Scaffold(body: AiMentorScreen()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.getBottomRight(find.text('最新回答')).dy, lessThan(700));
    },
  );
  testWidgets('a recommended skill starts the real scoped run only once', (
    tester,
  ) async {
    final api = ApiClient('http://localhost');
    var calls = 0;
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (r, h) {
          expect(r.path, '/agent-runs');
          expect(r.method, 'POST');
          expect(r.data['kind'], 'risk_brief');
          expect(r.data['subject']['student_id'], 'student');
          calls++;
          resolveData(r, h, {'run_id': 'skill-run', 'status': 'queued'});
        },
      ),
    );
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, s) => const Scaffold(
            body: ResearchSkillActions(
              skills: [
                {
                  'id': 'risk_overview',
                  'label': '检查任务安排',
                  'kind': 'risk_brief',
                  'subject': {'student_id': 'student'},
                  'input': {'horizon_days': 7},
                  'reason': '先找出需要处理的工作',
                },
              ],
            ),
          ),
        ),
        GoRoute(
          path: '/agent',
          builder: (_, s) =>
              Scaffold(body: Text('真实运行 ${s.uri.queryParameters['run_id']}')),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(api),
          sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          theme: ResearchDesign.theme(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('检查任务安排'));
    await tester.tap(find.text('检查任务安排'));
    await tester.pumpAndSettle();
    expect(calls, 1);
    expect(find.text('真实运行 skill-run'), findsOneWidget);
  });
  testWidgets(
    'the mentor chat keeps the phase plan collapsed until requested',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
            researchFollowupsProvider.overrideWith(
              (ref) => const AsyncData([]),
            ),
          ],
          child: MaterialApp(
            theme: ResearchDesign.theme(),
            home: const Scaffold(body: AiMentorScreen()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('查看当前推进计划'), findsOneWidget);
      expect(find.text('AI 当前推进计划'), findsNothing);
      expect(find.byTooltip('发送问题'), findsOneWidget);
    },
  );
  test('the overview hides technical task identifiers from prose', () {
    expect(
      readableResearchText(
        '整理计算记录（id:2c520f80-da4c-44c4-9b81-d5a78b83b5ee）的文档',
      ),
      '整理计算记录的文档',
    );
  });
  testWidgets(
    'long phase plans remain readable on a narrow screen with large text',
    (tester) async {
      final longText = List.filled(8, '核对台灯功率、使用时长与费用计算，整理导师要求的完整记录。').join();
      final rows = <Map<String, dynamic>>[
        {
          'id': 'old',
          'state': 'active',
          'run': {
            'status': 'succeeded',
            'result': {
              'summary': longText,
              'next_steps': [
                {'title': longText},
              ],
              'missing_inputs': ['参数表', '复核记录'],
            },
          },
          'changes': [
            {'reason': '导师补充了新的修改意见'},
          ],
        },
      ];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => FollowupTestSession(ref)),
            researchFollowupsProvider.overrideWith((ref) => AsyncData(rows)),
            assistantStagesProvider.overrideWith(
              (ref, id) async => [
                {'status': 'active', 'title': '台灯用电理论计算复核'},
              ],
            ),
          ],
          child: MaterialApp(
            theme: ResearchDesign.theme(),
            home: MediaQuery(
              data: const MediaQueryData(
                size: Size(320, 700),
                textScaler: TextScaler.linear(1.3),
              ),
              child: const Scaffold(
                body: SingleChildScrollView(
                  child: SizedBox(
                    width: 320,
                    child: ResearchPlanPanel(compact: true),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('AI 当前推进计划'), findsOneWidget);
      expect(find.text('当前阶段 · 台灯用电理论计算复核'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  test('a new running plan retains the last usable plan', () {
    final old = <String, dynamic>{
      'id': 'old',
      'state': 'applied',
      'run': {
        'status': 'succeeded',
        'result': {'summary': '先核对台灯计算单位'},
      },
    };
    final rows = <Map<String, dynamic>>[
      {
        'id': 'new',
        'state': 'active',
        'run': {'status': 'running'},
      },
      {
        'id': 'ignored',
        'state': 'dismissed',
        'run': {
          'status': 'succeeded',
          'result': {'summary': '不采用的建议'},
        },
      },
      old,
    ];
    expect(currentResearchPlan(rows)?['id'], 'old');
    expect(researchPlanUpdating(rows), isTrue);
  });
  test('no completed plan is represented as an empty state', () {
    expect(
      currentResearchPlan([
        {'state': 'waiting'},
      ]),
      isNull,
    );
    expect(
      researchPlanUpdating([
        {'state': 'waiting'},
      ]),
      isTrue,
    );
  });
}
