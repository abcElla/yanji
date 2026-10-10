import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/design_system.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main(){
  for(final mentor in [false,true]){
    testWidgets('overview ${mentor?'mentor':'student'} combines branded hero and four compact routes with actual counts',(tester)async{
      tester.view.physicalSize=const Size(360,1000);tester.view.devicePixelRatio=1;
      addTearDown(tester.view.resetPhysicalSize);addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(ProviderScope(overrides:[
        sessionProvider.overrideWith((ref)=>TestSession(ref,mentor:mentor)),roleProvider.overrideWith((ref)=>mentor?UserRole.mentor:UserRole.student),
        dashboardProvider.overrideWith((ref)async=>{'counters':{'tasks_total':8,'tasks_overdue':2,'students_total':3,'weekly_reports_submitted':4,'papers_ready':5,'theses_total':6},'risks':[]}),
        tasksProvider.overrideWith((ref)async=>[]),studentsProvider.overrideWith((ref)async=>[]),pendingMentorApplicationsProvider.overrideWith((ref)async=>[]),
      ],child:MaterialApp(theme:ResearchDesign.theme(),home:const Scaffold(body:OverviewScreen()))));
      await tester.pumpAndSettle();
      expect(find.text('研迹'),findsOneWidget);
      expect(find.text(mentor?'导师移动工作台':'学生移动工作台'),findsOneWidget);
      for(final key in ['overview-theses','overview-reports','overview-progress','overview-library']){
        final card=find.byKey(ValueKey(key));expect(card,findsOneWidget);expect(tester.getSize(card).width,lessThan(170));
      }
      expect(find.text('6 份档案'),findsOneWidget);expect(find.text('5 篇已就绪'),findsOneWidget);
      expect(tester.takeException(),isNull);
    });
  }
}
