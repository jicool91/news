package ru.gang.newsBot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsItem {
    private String title;
    private String url;
    private String source;
    private String imageUrl;
    private String description;
    private String category;
}