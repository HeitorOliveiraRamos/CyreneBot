package main;

import functions.ChatBot;
import functions.ModerationAI;
import functions.ReplyAI;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main extends ListenerAdapter {
    public static void main(String[] args) {
        final JDABuilder builder = JDABuilder.createDefault(
                Configuration.TOKEN,
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.DIRECT_MESSAGES,
                GatewayIntent.GUILD_MEMBERS);

        builder.addEventListeners(new ChatBot());
        builder.addEventListeners(new ReplyAI());
        builder.addEventListeners(new ModerationAI());

        builder.build();
    }
}
