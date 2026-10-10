package cn.yanji.service;

import static cn.yanji.core.Data.*;
import java.time.*;
import java.util.*;

/** Deterministic bilingual retrieval with relevance, recency, observed signal and MMR diversity. */
public final class ExperienceRanking {
  private ExperienceRanking() {}
  public static String clip(String text,int limit) {
    return text == null ? "" : text.substring(0,Math.min(limit,text.length()));
  }
  public static List<String> extract(String text) {
    var lessons=new LinkedHashSet<String>();
    for(String clause:clip(text,6000).split("[。！？；\\n]+")) {
      clause=clause.trim();
      if(clause.length()>=4) lessons.add(clip(clause,240));
      if(lessons.size()>=8)break;
    }
    return new ArrayList<>(lessons);
  }
  static Set<String> terms(String text) {
    var out=new HashSet<String>();
    String normalized=clip(text,6000).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ");
    for(String word:normalized.split(" +")) {
      if(word.isBlank())continue;
      out.add(word);
      for(int i=0;i+1<word.length();i++)out.add(word.substring(i,i+2));
    }
    return out;
  }
  public static double similarity(String a,String b) {
    var left=terms(a);var right=terms(b);
    if(left.isEmpty()||right.isEmpty())return 0;
    long overlap=left.stream().filter(right::contains).count();
    return overlap/Math.sqrt((double)left.size()*right.size());
  }
  public static List<Map<String,Object>> select(List<Map<String,Object>> candidates,String query,int limit,Instant observed) {
    var pool=new ArrayList<Map<String,Object>>();
    for(var candidate:candidates) {
      var row=new LinkedHashMap<>(candidate);
      double relevance=similarity(query,str(row,"lesson")+" "+Objects.toString(row.get("topic"),""));
      // Unrelated experiences must not enter a new research task just because they are recent.
      if(relevance<0.03)continue;
      double age=Math.max(0,Duration.between(instant(row.get("observed_at")),observed).toSeconds()/86400.0);
      double signal=((Number)row.getOrDefault("signal",0.2)).doubleValue();
      row.put("retrieval_score",0.65*relevance+0.20*Math.exp(-age/30.0)+0.15*signal);
      pool.add(row);
    }
    var selected=new ArrayList<Map<String,Object>>();
    while(!pool.isEmpty()&&selected.size()<limit) {
      Map<String,Object> best=null;double bestScore=-Double.MAX_VALUE;
      for(var row:pool) {
        double duplicate=selected.stream().mapToDouble(old->similarity(str(old,"lesson"),str(row,"lesson"))).max().orElse(0);
        double score=0.8*((Number)row.get("retrieval_score")).doubleValue()-0.2*duplicate;
        if(score>bestScore) {best=row;bestScore=score;}
      }
      selected.add(best);pool.remove(best);
    }
    return selected;
  }
}
