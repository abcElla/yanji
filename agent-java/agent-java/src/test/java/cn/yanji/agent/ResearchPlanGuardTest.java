package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.workflow.*;
import org.junit.jupiter.api.Test;

class ResearchPlanGuardTest {
  ResearchPlanGuard guard = new ResearchPlanGuard();

  @Test
  void parsesAndDeduplicatesQueriesWithoutExpandingScope() {
    var plan =
        guard.parse(
            Fixtures.object(
                "{\"goal\":\"核对材料\",\"queries\":[{\"query\":\" 重复 验证 \",\"reason\":\"找材料\"},{\"query\":\"重复 验证\",\"reason\":\"重复\"}]}"));
    assertThat(plan.queries()).hasSize(1);
    assertThat(plan.queries().get(0).query()).isEqualTo("重复 验证");
    assertThatThrownBy(() -> plan.queries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void blankQueriesAreRemovedAndZeroQueriesAreValid() {
    assertThat(
            guard
                .parse(
                    Fixtures.object(
                        "{\"goal\":\"缺材料\",\"queries\":[{\"query\":\"   \",\"reason\":\"\"}]}"))
                .queries())
        .isEmpty();
  }

  @Test
  void rejectsPermissionsToolsSqlAndOversizedProtocol() {
    for (String value :
        new String[] {
          "{\"goal\":\"x\",\"queries\":[],\"student_id\":\"other\"}",
          "{\"goal\":\"x\",\"queries\":[{\"query\":\"x\",\"reason\":\"x\",\"scope\":\"all\"}]}",
          "{\"goal\":\"x\",\"queries\":[{\"query\":\"https://evil.example\",\"reason\":\"x\"}]}",
          "{\"goal\":\"x\",\"queries\":[{\"query\":\"SELECT * FROM users\",\"reason\":\"x\"}]}",
          "{\"goal\":\"x\",\"queries\":[{\"query\":\"x\",\"reason\":\"x\"},{\"query\":\"y\",\"reason\":\"x\"},{\"query\":\"z\",\"reason\":\"x\"}]}"
        })
      assertThatThrownBy(() -> guard.parse(Fixtures.object(value)))
          .isInstanceOf(AgentFailure.class);
    var longPlan = Fixtures.object("{\"goal\":\"x\",\"queries\":[]}");
    longPlan.put("goal", "x".repeat(501));
    assertThatThrownBy(() -> guard.parse(longPlan)).isInstanceOf(AgentFailure.class);
  }

  @Test
  void explicitBudgetNeverAllowsThirdCall() {
    var budget = new ModelCallBudget(2);
    budget.reserve();
    budget.reserve();
    assertThat(budget.remaining()).isZero();
    assertThatThrownBy(budget::reserve).isInstanceOf(AgentFailure.class);
  }
}
