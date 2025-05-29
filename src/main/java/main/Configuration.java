package main;

public class Configuration {
    public static final String TOKEN = System.getenv("BOT_TOKEN");
    public static final String TEST_CHANNEL = System.getenv("TEST_CHANNEL_ID");
    public static final String TEST_USER = System.getenv("TEST_USER_ID");
    public static final String MODEL = System.getenv("MODEL_NAME");
    public static final String PERSONALITY = System.getenv("BOT_PERSONALITY");
    public static final String BASE_URL = "http://localhost:11434/api/chat";
}