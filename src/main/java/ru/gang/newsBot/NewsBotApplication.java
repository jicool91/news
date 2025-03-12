package ru.gang.newsBot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.springframework.context.annotation.Bean;
import ru.gang.newsBot.bot.NewsBot;

@SpringBootApplication
@EnableScheduling
public class NewsBotApplication {
    private static final Logger log = LoggerFactory.getLogger(NewsBotApplication.class);

    public static void main(String[] args) {
        ApplicationContext context = SpringApplication.run(NewsBotApplication.class, args);

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(context.getBean(NewsBot.class));
            log.info("Бот зарегистрирован через TelegramBotsApi");
        } catch (Exception e) {
            log.error("Ошибка при регистрации бота", e);
        }
    }

    @Bean
    public DefaultBotOptions botOptions() {
        return new DefaultBotOptions();
    }
}