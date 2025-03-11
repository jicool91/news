package ru.gang.newsBot.model;

public class NewsItem {
    private String title;
    private String url;
    private String source;
    private String imageUrl;
    private String description; // <-- новое поле

    public NewsItem(String title, String url, String source, String imageUrl, String description) {
        this.title = title;
        this.url = url;
        this.source = source;
        this.imageUrl = imageUrl;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getSource() {
        return source;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getDescription() {
        return description;
    }
}
