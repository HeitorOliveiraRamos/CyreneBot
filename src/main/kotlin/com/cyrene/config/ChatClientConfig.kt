package com.cyrene.config

import com.cyrene.discord.tools.DiscordTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds a [ChatClient] with all `@Tool`-annotated methods on [DiscordTools] pre-registered
 * as default callbacks. This is the entry point used for tool-aware chats (mentions and
 * `/iniciar-conversa` sessions). The legacy single-pass [OllamaAiService.chat] path used by
 * `/contexto-do-canal` calls the raw [OllamaChatModel] directly to avoid tool overhead.
 */
@Configuration
class ChatClientConfig {

    @Bean
    fun discordToolCallbacks(tools: DiscordTools): MethodToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(tools).build()

    @Bean
    fun chatClient(
        chatModel: OllamaChatModel,
        toolCallbacks: MethodToolCallbackProvider,
    ): ChatClient = ChatClient.builder(chatModel)
        .defaultToolCallbacks(toolCallbacks)
        .build()
}
