import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'data.dart';
import 'design_system.dart';
import 'models.dart';
import 'screens.dart';
import 'registration.dart';
import 'research_assistant.dart';
import 'guidance_page.dart';
import 'progress_page.dart';
import 'theses.dart';
import 'paper_reader.dart';
import 'research_followups.dart';

const _violet = Color(0xFF6C4CF1);

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: '/login',
    routes: [
      GoRoute(
          path: '/papers/:id',
          builder: (context, state) =>
              PaperReaderScreen(paperId: state.pathParameters['id']!)),
      GoRoute(
          path: '/theses', builder: (context, state) => const ThesesScreen()),
      GoRoute(
          path: '/theses/:id',
          builder: (context, state) =>
              ThesisDetailScreen(thesisId: state.pathParameters['id']!)),
      GoRoute(
          path: '/progress', builder: (context, state) => const ProgressPage()),
      GoRoute(
          path: '/research-followups/:id',
          builder: (context, state) =>
              ResearchFollowupDetailScreen(id: state.pathParameters['id']!)),
      GoRoute(
          path: '/guidance', builder: (context, state) => const GuidancePage()),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
          path: '/register',
          builder: (context, state) => const RegisterScreen()),
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(
              path: '/overview',
              builder: (context, state) => const OverviewScreen()),
          GoRoute(
              path: '/work',
              builder: (context, state) => WorkScreen(
                  initialTab:
                      state.uri.queryParameters['tab'] == 'reports' ? 1 : 0)),
          GoRoute(
              path: '/library',
              builder: (context, state) => const LibraryScreen()),
          GoRoute(
              path: '/profile',
              builder: (context, state) => const ProfileScreen()),
          GoRoute(
              path: '/ai-mentor',
              builder: (context, state) => const AiMentorScreen()),
        ],
      ),
      GoRoute(
          path: '/task', builder: (context, state) => const TaskDetailScreen()),
      GoRoute(
          path: '/agent',
          builder: (context, state) =>
              AgentRunScreen(runId: state.uri.queryParameters['run_id'])),
      GoRoute(
          path: '/assistant',
          builder: (context, state) => ResearchAssistantScreen(
              stageId: state.uri.queryParameters['stage_id'],
              paperId: state.uri.queryParameters['paper_id'],
              question: state.uri.queryParameters['question'])),
      GoRoute(
          path: '/agent-runs',
          builder: (context, state) => const AgentHistoryScreen()),
      GoRoute(
          path: '/report',
          builder: (context, state) => const WeeklyReportScreen()),
    ],
  );
  ref.listen(sessionProvider, (previous, next) {
    if (previous?.valueOrNull != null &&
        next.valueOrNull == null &&
        !next.isLoading) {
      router.go('/login');
    }
  });
  ref.onDispose(router.dispose);
  return router;
});

class YanjiApp extends ConsumerWidget {
  const YanjiApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      debugShowCheckedModeBanner: false,
      title: '研迹',
      locale: const Locale('zh', 'CN'),
      supportedLocales: const [Locale('zh', 'CN')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: ResearchDesign.theme(),
      routerConfig: ref.watch(routerProvider),
    );
  }
}

class AppShell extends ConsumerWidget {
  const AppShell({required this.child, super.key});
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final location = GoRouterState.of(context).uri.path;
    const paths = ['/overview', '/work', '/library', '/ai-mentor', '/profile'];
    final index = paths.indexOf(location).clamp(0, 4).toInt();
    return Scaffold(
      body: SafeArea(child: child),
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (value) => context.go(paths[value]),
        destinations: const [
          NavigationDestination(
              icon: Icon(Icons.space_dashboard_outlined),
              selectedIcon: Icon(Icons.space_dashboard),
              label: '总览'),
          NavigationDestination(
              icon: Icon(Icons.checklist_outlined),
              selectedIcon: Icon(Icons.checklist),
              label: '工作'),
          NavigationDestination(
              icon: Icon(Icons.library_books_outlined),
              selectedIcon: Icon(Icons.library_books),
              label: '资料'),
          NavigationDestination(
              icon: Icon(Icons.auto_awesome_outlined),
              selectedIcon: Icon(Icons.auto_awesome),
              label: 'AI助教'),
          NavigationDestination(
              icon: Icon(Icons.person_outline),
              selectedIcon: Icon(Icons.person),
              label: '我的'),
        ],
      ),
    );
  }
}

String statusLabel(TaskStatus status) => switch (status) {
      TaskStatus.todo => '待开始',
      TaskStatus.inProgress => '进行中',
      TaskStatus.blocked => '已阻塞',
      TaskStatus.submitted => '待验收',
      TaskStatus.completed => '已完成',
      TaskStatus.unsupported => '版本暂不支持',
    };

Color statusColor(TaskStatus status) => switch (status) {
      TaskStatus.completed => const Color(0xFF168568),
      TaskStatus.blocked => const Color(0xFFD45A3A),
      TaskStatus.submitted => const Color(0xFFBE7A12),
      _ => _violet,
    };
