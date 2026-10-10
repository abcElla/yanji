package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** ARIES directness labels test the rule extractor, not scientific correctness or complete agent quality. */
@EnabledIfSystemProperty(named="ariesDatasetPath",matches=".+")
class PublicFeedbackDatasetTest {
  @Test void reportDirectRequestRecognitionOnUnmodifiedPublicLabels() throws Exception {
    Path data=Path.of(System.getProperty("ariesDatasetPath"));
    var predictions=new ArrayList<Object>();int tp=0,fp=0,tn=0,fn=0;
    for(String line:Files.readAllLines(data)) {
      var row=obj(parse(line));Object label=obj(row.get("annotations")).get("comment_is_direct");
      if(!(label instanceof Number))continue;
      int truth=((Number)label).intValue();
      boolean predicted=Boolean.TRUE.equals(FeedbackExperienceExtractor.describe(str(row,"comment"),"scientific review").get("explicit_feedback"));
      if(predicted&&truth==1)tp++;else if(predicted)fp++;else if(truth==1)fn++;else tn++;
      predictions.add(map("doc_id",row.get("doc_id"),"comment_id",row.get("comment_id"),"gold_direct",truth,"predicted_direct",predicted));
    }
    int count=tp+fp+tn+fn;assertThat(count).isGreaterThan(0);
    double precision=tp+fp==0?0:(double)tp/(tp+fp),recall=tp+fn==0?0:(double)tp/(tp+fn);
    var report=map("dataset","ARIES generated_edits expert directness annotations","task","direct_request_recognition_diagnostic",
        "samples",count,"source_sha256",hash(Files.readAllBytes(data)),"algorithm","rule-feedback-extractor-v2",
        "accuracy",(double)(tp+tn)/count,"precision",precision,"recall",recall,"f1",precision+recall==0?0:2*precision*recall/(precision+recall),
        "confusion",map("tp",tp,"fp",fp,"tn",tn,"fn",fn),"predictions",predictions,
        "limitations",List.of("No fitting or threshold tuning on these labels","Not feedback compliance, edit correctness, or full agent accuracy","English public reviews differ from Chinese mentor interactions"));
    Path output=Path.of("target/aries-feedback-diagnostic.json");Files.writeString(output,json(report));
    System.out.println("ARIES diagnostic samples="+count+" accuracy="+report.get("accuracy")+" f1="+report.get("f1"));
  }
}
