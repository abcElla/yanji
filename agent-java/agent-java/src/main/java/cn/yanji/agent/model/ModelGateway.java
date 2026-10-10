package cn.yanji.agent.model;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ModelGateway {
  default cn.yanji.agent.workflow.ResearchPlan plan(ObjectNode modelContext) {
    throw new cn.yanji.agent.contract.AgentFailure("PLANNER_UNSUPPORTED", "当前模型适配器不支持受限规划", false);
  }

  ObjectNode generate(ObjectNode context, ArrayNode evidence, ArrayNode risks);

  default com.fasterxml.jackson.databind.node.ArrayNode extractFeedback(ObjectNode context) {
    throw new cn.yanji.agent.contract.AgentFailure(
        "FEEDBACK_EXTRACTION_UNSUPPORTED", "模型不支持反馈提取", false);
  }
}
