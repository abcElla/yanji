package cn.yanji.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * Jieba 0.42.1 DAG segmentation and search expansion, HMM disabled as in the old backend. Derived
 * algorithm and bundled dictionary: Copyright (c) 2013 Sun Junyi, MIT license.
 */
public final class LexicalSearch {
  private LexicalSearch() {}

  private static final Map<String, Integer> FREQUENCY = new HashMap<>();
  private static final double LOG_TOTAL;
  private static final Pattern BLOCK = Pattern.compile("[\\u4E00-\\u9FD5a-zA-Z0-9+#&._%\\-]+");
  private static final Set<String> STOP =
      Set.of(
          "如何", "什么", "哪些", "请问", "请", "分析", "根据", "目前", "这个", "这些", "我们", "是否", "可以", "需要", "进行",
          "以及", "一个", "当前", "说明", "告诉", "使用", "问题");

  static {
    long total = 0;
    try (var in =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource("jieba-dict.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        String[] fields = line.split(" ");
        String word = fields[0];
        int frequency = Integer.parseInt(fields[1]);
        FREQUENCY.put(word, frequency);
        total += frequency;
        for (int i = 1; i < word.length(); i++) FREQUENCY.putIfAbsent(word.substring(0, i), 0);
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    LOG_TOTAL = Math.log(total);
  }

  static List<String> segment(String block) {
    int n = block.length();
    double[] score = new double[n + 1];
    int[] next = new int[n];
    for (int i = n - 1; i >= 0; i--) {
      double best = Double.NEGATIVE_INFINITY;
      int end = i;
      boolean found = false;
      for (int j = i + 1; j <= n; j++) {
        Integer frequency = FREQUENCY.get(block.substring(i, j));
        if (frequency == null) break;
        if (frequency == 0) continue;
        found = true;
        double candidate = Math.log(frequency) - LOG_TOTAL + score[j];
        if (candidate >= best) {
          best = candidate;
          end = j - 1;
        }
      }
      if (!found) best = -LOG_TOTAL + score[i + 1];
      score[i] = best;
      next[i] = end + 1;
    }
    var words = new ArrayList<String>();
    StringBuilder ascii = new StringBuilder();
    for (int i = 0; i < n; ) {
      int end = next[i];
      String word = block.substring(i, end);
      if (word.length() == 1 && word.matches("[a-zA-Z0-9]")) ascii.append(word);
      else {
        if (!ascii.isEmpty()) {
          words.add(ascii.toString());
          ascii.setLength(0);
        }
        words.add(word);
      }
      i = end;
    }
    if (!ascii.isEmpty()) words.add(ascii.toString());
    return words;
  }

  public static Set<String> terms(String query) {
    String text = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    var matcher = BLOCK.matcher(text);
    var terms = new LinkedHashSet<String>();
    while (matcher.find())
      for (String word : segment(matcher.group())) {
        if (word.length() > 2)
          for (int i = 0; i < word.length() - 1; i++) {
            String gram = word.substring(i, i + 2);
            if (FREQUENCY.getOrDefault(gram, 0) > 0) add(terms, gram);
          }
        if (word.length() > 3)
          for (int i = 0; i < word.length() - 2; i++) {
            String gram = word.substring(i, i + 3);
            if (FREQUENCY.getOrDefault(gram, 0) > 0) add(terms, gram);
          }
        add(terms, word);
      }
    return new LinkedHashSet<>(terms.stream().limit(64).toList());
  }

  static void add(Set<String> terms, String word) {
    if (word.length() >= 2 && !STOP.contains(word) && word.matches("[\\p{L}\\p{N}_]+"))
      terms.add(word);
  }

  public static long score(String text, Set<String> terms) {
    text = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    long score = 0;
    for (String term : terms)
      for (int offset = 0; offset < text.length(); ) {
        int index = text.indexOf(term, offset);
        if (index < 0) break;
        score++;
        offset = index + term.length();
      }
    return score;
  }
}
