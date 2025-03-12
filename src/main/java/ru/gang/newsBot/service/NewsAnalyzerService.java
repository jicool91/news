package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NewsAnalyzerService {
    private static final Logger log = LoggerFactory.getLogger(NewsAnalyzerService.class);

    private final List<String> stopWords = List.of("в", "на", "и", "по", "за", "что", "из", "с", "как", "от", "о");

    public Map<String, Integer> analyzeNews(List<String> headlines) {
        log.info("Начало анализа {} заголовков", headlines.size());
        Map<String, Integer> wordFrequency = new HashMap<>();

        for (String headline : headlines) {
            String[] words = headline.replaceAll("[^а-яА-Я ]", "").toLowerCase().split("\\s+");
            for (String word : words) {
                if (!stopWords.contains(word) && word.length() > 2) {
                    wordFrequency.put(word, wordFrequency.getOrDefault(word, 0) + 1);
                }
            }
        }

        Map<String, Integer> result = sortByValue(wordFrequency);
        log.info("Анализ завершен, найдено {} уникальных слов, выбрано топ {}", 
                wordFrequency.size(), result.size());
        return result;
    }

    private Map<String, Integer> sortByValue(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        Map<String, Integer> sortedMap = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            sortedMap.put(list.get(i).getKey(), list.get(i).getValue());
        }
        return sortedMap;
    }
}
