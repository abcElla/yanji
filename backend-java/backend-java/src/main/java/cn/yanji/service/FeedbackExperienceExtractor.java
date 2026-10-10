package cn.yanji.service;

import static cn.yanji.core.Data.*;
import java.util.*;

/** Conservative rule-based extraction: the source quote remains the authority, not the label. */
public final class FeedbackExperienceExtractor {
  private FeedbackExperienceExtractor() {}

  public static Map<String,Object> describe(String quote, String topic) {
    String category="general_guidance", scenario="同一项目的相似科研任务";
    if(quote.matches("(?is).*(消融|ablation).*")) {category="ablation";scenario="实验设计与模块贡献分析";}
    else if(quote.matches("(?is).*(基线|baseline|对照).*")) {category="baseline";scenario="基线比较与对照实验";}
    else if(quote.matches("(?is).*(划分|训练集|测试集|验证集).*")) {category="data_split";scenario="数据集准备与实验比较";}
    else if(quote.matches("(?is).*(笼统|具体|验收|可执行).*")) {category="specificity";scenario="任务规划与验收标准";}
    else if(quote.matches("(?is).*(前置|依赖|先.+再|前提).*")) {category="prerequisite";scenario="多步骤科研任务规划";}
    else if(quote.matches("(?is).*(证据|引用|来源|原始记录).*")) {category="evidence";scenario="证据核对与科研写作";}
    else if(quote.matches("(?is).*(预算|时长|时间|资源|显存).*")) {category="resources";scenario="资源受限的研究安排";}
    boolean temporary=quote.matches("(?is).*(这次|本次|当前|本周|此稿|这篇|该学生|暂时|暂不|this week|this draft|temporarily).* ".trim());
    String polarity=quote.matches("(?is).*(不要|无需|不必|暂不|do not|don't|not necessary).* ".trim())?"avoid":"consider";
    boolean explicit=quote.matches("(?is).*(要求|建议|应该|请|需要|必须|缺少|补充|不要|无需|不必|\\bshould\\b|\\bmust\\b|\\bplease\\b|\\bneed\\b|\\bsuggest\\b|\\brecommend\\b|\\badd\\b|\\binclude\\b|\\bclarify\\b|\\bexplain\\b).* ".trim());
    return map("category",category,"applicable_scenario",scenario,"improvement_request",quote,
        "source_quote",quote,"reuse_scope",temporary?"temporary_requirement":"project_local",
        "polarity",polarity,"explicit_feedback",explicit,"topic",topic,
        "generalization_requires_confirmation",true);
  }
}
