package ru.gang.newsBot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NewsAnalyzerService {

    private final List<String> stopWords = List.of("в", "на", "и", "по", "за", "что", "из", "с", "как", "от", "о");

    public Map<String, Integer> analyzeNews(List<String> headlines) {
        log.info("Начало анализа {} заголовков", headlines.size());

        Map<String, Integer> wordFrequency = headlines.stream()
                .flatMap(headline -> Arrays.stream(headline.replaceAll("[^а-яА-Я ]", "").toLowerCase().split("\\s+")))
                .filter(word -> !stopWords.contains(word) && word.length() > 2)
                .collect(Collectors.groupingBy(
                        word -> word,
                        Collectors.summingInt(e -> 1)
                ));

        return wordFrequency.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
}