package com.cyrene.discord.command

import com.cyrene.conversation.UsuarioService
import com.cyrene.discord.util.BotMessages
import com.cyrene.discord.util.DiscordMessageSender
import com.cyrene.hsr.BuildAnalyzer
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.hsr.MihomoCharacter
import com.cyrene.hsr.MihomoClient
import com.cyrene.hsr.MihomoResult
import com.cyrene.hsr.ScoreWeights
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `/build <personagem>` — rates the caller's showcased build with real numbers, end to end
 * in code: linked UID → mihomo showcase fetch → [BuildAnalyzer] (StarRailScore formula) →
 * rendered verdict. No LLM in the loop, so the reply is fast and the numbers can't be
 * hallucinated; no InferenceGate either, since Ollama is never touched.
 */
@Component
class BuildCommand(
    private val usuarioService: UsuarioService,
    private val mihomo: MihomoClient,
    private val scoreWeights: ScoreWeights,
    private val hsrCharacters: HsrCharacterService,
    private val sender: DiscordMessageSender,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "build"

    override val definition: CommandData =
        Commands.slash(name, "Avalio a build de uma personagem da sua vitrine (relíquias, nota e o que farmar)")
            .addOption(OptionType.STRING, "personagem", "Nome da personagem (precisa estar na sua vitrine do jogo)", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val query = event.getOption("personagem")?.asString?.trim().orEmpty()
        val uid = usuarioService.uidDe(event.user.id)
        if (uid == null) {
            event.reply(BotMessages.BUILD_NO_UID).setEphemeral(true).queue()
            return
        }
        event.deferReply().queue()

        try {
            CompletableFuture
                .supplyAsync({ answer(uid, query) }, executor)
                .whenComplete { answer, ex ->
                    if (ex != null) {
                        log.error("/build failed for uid {} query '{}'", uid, query, ex)
                        event.hook.sendMessage(BotMessages.ERROR).queue()
                    } else {
                        sender.sendLong(event.hook, answer)
                    }
                }
        } catch (e: Exception) {
            log.error("/build could not submit work", e)
            event.hook.sendMessage(BotMessages.ERROR).queue()
        }
    }

    private fun answer(uid: String, query: String): String {
        val profile = when (val result = mihomo.fetch(uid)) {
            is MihomoResult.Ok -> result.profile
            MihomoResult.NotFound -> return BotMessages.buildUidNotFound(uid)
            MihomoResult.Error -> return BotMessages.ERROR
        }
        // Showcase names come localized from mihomo (pt); the id fallback lets a user type
        // the English/Spanish name ("firefly") and still land on the right showcased character.
        val character = matchCharacter(query, profile.characters)
            ?: hsrCharacters.resolveId(query)?.let { id -> profile.characters.firstOrNull { it.id == id } }
            ?: return BotMessages.buildNotInShowcase(query, profile.characters.map { it.name })
        val meta = hsrCharacters.fribbelsMeta(character.id)
        // StarRailScore is the primary ruler; fribbels weights cover characters it lags on.
        val srsWeights = scoreWeights.forCharacter(character.id)
        val weights = srsWeights
            ?: meta?.let { BuildAnalyzer.fribbelsWeights(it) }
            ?: return BotMessages.buildNoWeights(character.name)
        val ruler = if (srsWeights != null) "StarRailScore" else "fribbels/hsr-optimizer"
        return BuildAnalyzer.render(character, BuildAnalyzer.analyze(character, weights), meta, ruler)
    }

    internal companion object {

        /** Accent/case-insensitive matching, shared with the hsr_character name resolver. */
        internal fun normalize(s: String): String = HsrCharacterService.normalize(s)

        /**
         * Picks the showcased character the user meant: exact normalized match first, then a
         * single containment match in either direction ("dan heng" → "Dan Heng • Lua Imbibitora").
         * Ambiguous containment (two matches) returns null — better to list the showcase than
         * to guess the wrong character and rate it.
         */
        internal fun matchCharacter(query: String, characters: List<MihomoCharacter>): MihomoCharacter? {
            val q = normalize(query)
            if (q.isEmpty()) return null
            characters.firstOrNull { normalize(it.name) == q }?.let { return it }
            val contains = characters.filter { normalize(it.name).contains(q) || q.contains(normalize(it.name)) }
            return contains.singleOrNull()
        }
    }
}
