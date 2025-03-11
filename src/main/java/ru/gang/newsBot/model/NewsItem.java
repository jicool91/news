package ru.gang.newsBot.model;


import lombok.Getter;

@Getter
public class NewsItem {
    private String title;
    private String url;
    private String source;
    private String imageUrl;
    private String description;
    private String category;

    public NewsItem(String title, String url, String source, String imageUrl, String description, String category) {
        this.title = title;
        this.url = url;
        this.source = source;
        this.imageUrl = imageUrl;
        this.description = description;
        this.category = category;
    }
}
