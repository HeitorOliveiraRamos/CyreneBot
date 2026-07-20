package com.cyrene.ai

import com.cyrene.config.BotProperties
import com.cyrene.conversation.ConversationMessage
import com.cyrene.conversation.MessageRole
import com.cyrene.discord.util.BotMessages
import com.cyrene.hsr.HsrCharacterService
import com.cyrene.knowledge.AnswerCache
import com.cyrene.knowledge.BuildAnswerService
import com.cyrene.knowledge.Grounding
import com.cyrene.knowledge.ItemAnswerService
import com.cyrene.knowledge.KitAnswerService
import com.cyrene.knowledge.KnowledgeGrounder
import com.cyrene.knowledge.LoreAnswerService
import com.cyrene.knowledge.RosterAnswerService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Orchestrates the bot's LLM access. The model has NO tools: every pass here is
 * text-in/text-out against [OllamaChatModel], and the only two things the bot answers are
 * conversation (persona) and Honkai: Star Rail questions (deterministically grounded).
 * Discord moderation is not reachable from here at all — it lives in explicit slash
 * commands under `com.cyrene.discord.command`, where Discord itself enforces who may run
 * what. That is the structural guarantee: a model that cannot call a tool cannot moderate
 * anyone, no matter what a message talks it into.
 *
 * Three routes out of [respond]:
 *
 *   1. **Command hint** — [commandHintFor]. A prose request for a server action is answered
 *      with the slash command that does it. Decided in code, no LLM call.
 *   2. **Knowledge** — [runKnowledgePipeline], when the intent gate says "kb". Retrieval is
 *      done in code, then the voice retells it. Persona applies only to the retelling.
 *   3. **Chat** — [runVoicePassConversational]. Persona-only, straight to the answer.
 *
 * Both use [BotProperties.voiceModelName] for prose; the gate uses the smaller/hotter
 * [BotProperties.brainModelName] for its one-word classification. The legacy [chat] path
 * is a single plain pass for /contexto-do-canal.
 */
@Service
class OllamaAiService(
    private val chatModel: OllamaChatModel,
    private val promptBuilder: PromptBuilder,
    private val postProcessor: ResponsePostProcessor,
    private val properties: BotProperties,
    private val metrics: AiMetrics,
    private val knowledgeGrounder: KnowledgeGrounder,
    private val characters: HsrCharacterService,
    private val answerCache: AnswerCache,
    private val buildAnswers: BuildAnswerService,
    private val kitAnswers: KitAnswerService,
    private val itemAnswers: ItemAnswerService,
    private val loreAnswers: LoreAnswerService,
    private val rosterAnswers: RosterAnswerService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // -------------------- Public API -------------------- //

    /**
     * Answers a mention reply or an `/iniciar-conversa` session turn. Three outcomes, in
     * order: a prose request for a server action is answered with the slash command that
     * does it ([commandHintFor], no LLM call), an HSR question is grounded and retold, and
     * everything else is persona chat.
     *
     * [progress] receives user-facing status lines ([BotMessages.STATUS_KNOWLEDGE] etc.) as
     * the pipeline crosses stage boundaries, so the listener can show what is happening
     * while a slow local generation runs. The CHAT route emits nothing — it's quick and
     * the typing indicator already covers it.
     */
    fun respond(
        history: List<ConversationMessage>,
        extraSystemPrompt: String? = null,
        userName: String? = null,
        progress: (String) -> Unit = {},
    ): String {
        // Someone asking for a moderation action in prose gets pointed at the command that
        // does it — decided in code, so the reply always names a command that exists and
        // costs no LLM call. See [commandHintFor] for why it errs toward staying quiet.
        val lastUser = history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        commandHintFor(lastUser)?.let {
            metrics.count("cyrene.command_hint", "command", it)
            if (log.isDebugEnabled) log.debug("Command hint → /{}", it)
            return BotMessages.useCommand(it)
        }

        val intent = classifyIntent(history)
        if (log.isDebugEnabled) {
            log.debug("Intent gate → {}", intent)
        }

        // KNOWLEDGE grounding is enforced in code (retrieve → abstain-or-retell), so the
        // model can't skip the source or invent when it comes back empty.
        if (intent == Intent.KNOWLEDGE) {
            return runKnowledgePipeline(history, extraSystemPrompt, userName, progress)
        }
        return runVoicePassConversational(history, extraSystemPrompt, userName)
    }

    /**
     * Direct entry into the KNOWLEDGE pipeline for the `/hsr` slash command. Skips the
     * intent gate (a /hsr question is HSR by definition, so it can never be misrouted as
     * chat) — same grounding guarantees as a routed KNOWLEDGE mention.
     */
    fun answerHsrQuestion(question: String, userName: String? = null): String {
        val history = listOf(
            ConversationMessage(conversationId = 0L, role = MessageRole.USER, content = question),
        )
        return runKnowledgePipeline(history, extraSystemPrompt = null, userName = userName, progress = {})
    }

    /**
     * KNOWLEDGE path: ground first, then retell. Retrieval is deterministic ([KnowledgeGrounder]):
     * local KB, then web, never the model's choice. When nothing real comes back we ABSTAIN with a
     * fixed in-voice line instead of letting the voice pass narrate an invented kit — this is the
     * guardrail that makes "Lilita / caminho Eclipse" structurally impossible.
     *
     * Speed: the common case (an established character found locally) is a single LLM call —
     * the voice retelling. The verifier only runs on web-sourced answers (local KB is
     * authoritative) and only when
     * [BotProperties.Knowledge.verifyWebAnswers] is on, so it never taxes the fast local path.
     */
    private fun runKnowledgePipeline(
        history: List<ConversationMessage>,
        extraSystemPrompt: String?,
        userName: String?,
        progress: (String) -> Unit,
    ): String {
        val question = history.lastOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()
        // Expand player shorthands ("dan heng il" → "Dan Heng - Embebidor Lunae") the KB has no
        // name for, so every downstream matcher anchors the right variant instead of the base.
        val searchQuery = HsrCharacterService.expandNicknames(condenseFollowUp(history, question))

        // Deterministic paths: build-facet, specific-ability/eidolon and item-effect
        // questions the KB has docs for are rendered by code (BuildAnswerService /
        // KitAnswerService / ItemAnswerService) — no retrieval ranking, no voice retell,
        // no verifier, so the layout is identical every time and a fact can never migrate
        // between items. The
        // model's only job is the one-line greeting on top ([greetingLine], user request
        // 2026-07-16 — canned fallback if it fails). Sits BEFORE the cache so stale
        // model-written answers can't mask it; the body is not cached itself (~ms render).
        buildAnswers.answer(searchQuery)?.let {
            return greeted(it, question, userName, "build_render", progress)
        }
        kitAnswers.answer(searchQuery)?.let {
            return greeted(it, question, userName, "kit_render", progress)
        }
        // Lore BEFORE item: both answer to "história"/"descrição", but lore additionally requires a
        // named CHARACTER, so it is the more specific of the two. With item first, "me conta a
        // história da himeko" was answered with a light cone whose name contains "História".
        // Full-history lore renders verbatim here; the SUMMARY-shaped ask deliberately returns
        // null and is picked up by the grounder's lore tier instead, so the voice pass condenses
        // it (see [LoreAnswerService]).
        loreAnswers.answer(searchQuery)?.let {
            return greeted(it, question, userName, "lore_render", progress)
        }
        itemAnswers.answer(searchQuery)?.let {
            return greeted(it, question, userName, "item_render", progress)
        }
        // Roster filters last: it's the only path that answers WITHOUT a named character, so it
        // must not pre-empt a question that named one ("quais personagens combinam com a march").
        rosterAnswers.answer(searchQuery)?.let {
            return greeted(it, question, userName, "roster_render", progress)
        }

        // Answer cache: a repeat question (exact normalized match) skips retrieval, voice
        // AND verify. Only grounded/verified answers ever get stored, so a hit is as safe
        // as the pipeline run that produced it.
        answerCache.get(searchQuery)?.let {
            metrics.count("cyrene.knowledge", "result", "cache_hit")
            log.debug("Knowledge cache hit for '{}'", searchQuery)
            return it
        }

        progress(BotMessages.STATUS_KNOWLEDGE)
        val grounding = metrics.timePass("knowledge_retrieve") {
            knowledgeGrounder.ground(searchQuery) { progress(BotMessages.STATUS_WEB) }
        }

        if (!grounding.found) {
            metrics.count("cyrene.knowledge", "result", "abstain")
            log.debug("Knowledge abstain: no source for '{}'", question)
            return BotMessages.knowledgeMiss(userName)
        }

        progress(BotMessages.STATUS_WRITING)
        var answer = runVoicePassKnowledge(history, grounding.context, extraSystemPrompt, userName, grounding.summarize)

        // Language guard: CJK-heavy source chunks make the voice model drift into Chinese
        // mid-answer, and the persona's "PT-BR only" rule can't win against a context that
        // is mostly CJK. Drift is stochastic, so one re-roll usually lands in PT-BR; if it
        // drifts twice we abstain rather than reply in the wrong language. Deterministic
        // check — the grounding judge can't catch this (a CN answer matches a CN source).
        if (hasCjk(answer)) {
            metrics.count("cyrene.knowledge", "result", "language_retry")
            log.debug("Knowledge answer drifted into CJK for '{}'; re-rolling voice pass", question)
            answer = runVoicePassKnowledge(history, grounding.context, extraSystemPrompt, userName, grounding.summarize)
        }
        if (answer.isBlank() || hasCjk(answer)) {
            metrics.count("cyrene.knowledge", "result", "language_abstain")
            return BotMessages.knowledgeMiss(userName)
        }

        // Web answers are the embellishment-prone ones (raw page text, less structured than the KB),
        // so a cheap judge checks the draft against the source. Fail-open on any error: a flaky judge
        // must never silence a genuinely-grounded reply.
        if (grounding.source == Grounding.Source.WEB && properties.knowledge.verifyWebAnswers) {
            if (!isGrounded(question, answer, grounding.context)) {
                metrics.count("cyrene.knowledge", "result", "unverified")
                log.debug("Knowledge verify rejected a web answer for '{}'", question)
                return BotMessages.knowledgeMiss(userName)
            }
            metrics.count("cyrene.knowledge", "result", "verified")
        } else {
            metrics.count("cyrene.knowledge", "result", "grounded")
        }
        answerCache.put(searchQuery, answer, grounding.source, userName)
        return answer
    }

    /**
     * Wraps a code-rendered [body] (build/kit path) with a model-written greeting line.
     * The body is final and untouchable — the model never sees it, so it physically
     * cannot corrupt the data; it only reacts to the [question] in persona.
     */
    private fun greeted(
        body: String,
        question: String,
        userName: String?,
        metric: String,
        progress: (String) -> Unit,
    ): String {
        metrics.count("cyrene.knowledge", "result", metric)
        progress(BotMessages.STATUS_WRITING)
        return "${greetingLine(question, userName)}\n\n$body"
    }

    /**
     * One in-persona opening line for a code-rendered body. The model sees ONLY the
     * subject (character names), never the question — given the question, the live smoke
     * test had GAIA fabricating mechanics in the opener ("a ult te joga pro inferno"),
     * the exact failure this path exists to kill; without it there is nothing to fake.
     * Runs on the BRAIN model, not GAIA: 14b followed the one-line contract 8/8 while
     * GAIA ran past the opener into invented data 2/4 — and the brain is already hot
     * here (the intent gate just ran on it), so it's the faster choice too. Tiny token
     * budget; ANY failure or fishy output falls back to the canned opener.
     */
    private fun greetingLine(question: String, userName: String?): String {
        val fallback = { BotMessages.answerOpener(userName, question.hashCode()) }
        val subject = characters.findInText(question)
            .mapNotNull { it.namePt ?: it.nameEn }.distinct().joinToString(" e ")
            .ifBlank { "Honkai: Star Rail" }
        val messages = promptBuilder.build(
            history = listOf(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = "[assunto da pergunta: $subject]",
                ),
            ),
            extraSystemPrompt = GREETING_INSTRUCTIONS,
            overrideOnly = false,
            userName = userName,
        )
        return try {
            val raw = metrics.timePass("voice_greeting") {
                chatModel.call(Prompt(messages, greetingOptions())).result.output.text
            } ?: ""
            sanitizeGreeting(postProcessor.process(raw)) ?: fallback()
        } catch (e: Exception) {
            log.warn("Greeting pass failed; using canned opener", e)
            fallback()
        }
    }

    /**
     * Condense-question step: retrieval sees only ONE query string, so a threaded follow-up
     * like "e os Eidolons dela?" would reach the vector store with an unresolved pronoun and
     * miss. When there is prior conversation, one cheap deterministic call rewrites the last
     * question as standalone; single-turn questions (and /hsr) skip it entirely. Fail-open:
     * any error, blank or fishy output falls back to the original question — the worst case
     * is exactly today's behaviour, never a corrupted query.
     */
    private fun condenseFollowUp(history: List<ConversationMessage>, question: String): String {
        if (history.size <= 1 || question.isBlank()) return question
        val transcript = history.dropLast(1).takeLast(6).joinToString("\n") { m ->
            val speaker = if (m.role == MessageRole.ASSISTANT) "Cyrene" else "Usuário"
            "$speaker: ${m.content.take(400)}"
        }
        val messages = listOf(
            SystemMessage(CONDENSE_INSTRUCTIONS),
            UserMessage("Conversa anterior:\n$transcript\n\nÚltima pergunta: $question\n\nPergunta reescrita:"),
        )
        return try {
            val raw = metrics.timePass("knowledge_condense") {
                chatModel.call(Prompt(messages, condenseOptions())).result.output.text
            } ?: ""
            val rewritten = sanitizeCondensed(raw, question)
            if (log.isDebugEnabled) log.debug("Condense '{}' → '{}'", question, rewritten)
            rewritten
        } catch (e: Exception) {
            log.warn("Condense step failed; grounding on the raw question", e)
            question
        }
    }

    /**
     * Grounding judge (#5): does every factual claim in [answer] trace back to [context]? Tiny token
     * budget, temp 0 — it only emits "sim"/"nao". Returns true on any failure (parse error, exception)
     * so the worst case is a missed catch, never a wrongly-suppressed good answer.
     */
    private fun isGrounded(question: String, answer: String, context: String): Boolean {
        val messages = listOf(
            SystemMessage(VERIFY_INSTRUCTIONS),
            UserMessage(
                "DATA DE HOJE: ${LocalDate.now()}\n\nPERGUNTA DO USUÁRIO:\n$question\n\n" +
                    "CONTEXTO:\n$context\n\nRESPOSTA:\n$answer\n\nVeredito:",
            ),
        )
        return try {
            val raw = metrics.timePass("knowledge_verify") {
                chatModel.call(Prompt(messages, verifyOptions())).result.output.text
            } ?: ""
            if (log.isDebugEnabled) log.debug("Grounding verdict raw='{}'", raw.trim())
            parseVerdict(raw)
        } catch (e: Exception) {
            log.warn("Grounding verification failed; passing answer through", e)
            true
        }
    }

    /**
     * Lightweight binary classifier: "kb" (run the grounded HSR pipeline) vs "chat" (answer
     * from persona). Uses [BotProperties.brainModelName] with very tight sampling and a tiny
     * token budget — it only needs to output one word. Failures (parse errors, exceptions)
     * default to [Intent.CHAT]: a question wrongly answered as chat costs a "não sei",
     * while chat wrongly answered as kb costs a kit dump on top of someone's joke.
     */
    private fun classifyIntent(history: List<ConversationMessage>): Intent {
        val lastUser = history.lastOrNull { it.role == MessageRole.USER }?.content?.trim()
        if (lastUser.isNullOrBlank()) return Intent.CHAT

        // Heuristic fast-path: skip the gate's LLM round-trip for unambiguous greetings/
        // thanks (the most common mention).
        fastPathIntent(lastUser)?.let {
            metrics.count("cyrene.llm.fastpath", "result", "hit")
            if (log.isDebugEnabled) log.debug("Intent fast-path (no LLM) → {}", it)
            return it
        }

        // Gazetteer fast-path: a message naming a known HSR character (any language, from
        // the hsr_character cache) PLUS an explicit mechanics word (kit/build/cone/…) routes
        // straight to KNOWLEDGE, LLM-free. Anything softer — a bare '?', a generic question
        // word, banter — defers to the LLM gate, which sees conversation context and can
        // keep a joke in chat.
        gazetteerFastPath(
            lastUser,
            mentionsCharacter = { characters.findInText(it).isNotEmpty() },
            isTableQuestion = { rosterAnswers.isTableQuestion(it) },
        )?.let {
            metrics.count("cyrene.llm.fastpath", "result", "gazetteer")
            if (log.isDebugEnabled) log.debug("Gazetteer fast-path (no LLM) → {}", it)
            return it
        }
        metrics.count("cyrene.llm.fastpath", "result", "miss")

        val messages = listOf(
            SystemMessage(INTENT_GATE_INSTRUCTIONS),
            UserMessage(gateUserBlock(history, lastUser)),
        )
        return try {
            val response = metrics.timePass("intent_gate") { chatModel.call(Prompt(messages, intentGateOptions())) }
            val raw = response.result.output.text ?: ""
            val intent = parseIntent(raw)
            if (log.isDebugEnabled) log.debug("Intent gate raw='{}' → {}", raw.trim(), intent)
            intent
        } catch (e: Exception) {
            log.warn("Intent gate failed; defaulting to CHAT", e)
            Intent.CHAT
        }
    }

    /**
     * Routing classes for the intent gate. [KNOWLEDGE] takes the grounded HSR pipeline;
     * [CHAT] — everything else, and the default whenever the gate is unsure — goes straight
     * to the persona voice.
     */
    internal enum class Intent { CHAT, KNOWLEDGE }

    /**
     * Knowledge voice call: persona + a "retell these HSR facts completely" directive,
     * carrying the user's original question AND the brain's gathered data as the payload.
     *
     * Differs from [runVoicePassFocused] in two ways that matter for a kit:
     *  - it does NOT cap length and explicitly overrides the persona's hard 1–3 sentence
     *    rule for this answer, so a full kit comes through whole instead of crushed —
     *    but depth is scoped to the QUESTION: "melhor equipe da cipher" gets the team
     *    line, not the whole build doc that rode along in the retrieval;
     *  - it includes the user's question, so depth cues ("kit completo", "lvl 999",
     *    "pesquisa na internet") reach the voice instead of being stripped away.
     * Uses [knowledgeVoiceOptions] (larger token budget, lower temperature for fidelity).
     */
    private fun runVoicePassKnowledge(
        history: List<ConversationMessage>,
        brainResult: String,
        extraSystemPrompt: String?,
        userName: String?,
        summarize: Boolean = false,
    ): String {
        val instruction = """
            ## Sua tarefa agora

            Outra etapa do bot pesquisou dados de Honkai: Star Rail (base local e/ou
            internet) para responder à pergunta do usuário. Sua função é repassar esses
            dados em personagem, em PT-BR.

            Regras desta etapa (têm prioridade sobre o limite de tamanho da sua persona):
            - Os dados fornecidos abaixo são TUDO o que você sabe sobre o assunto. Você
              NÃO tem memória própria sobre o jogo. Habilidade, traço, Eidolon, número,
              efeito ou nome que NÃO aparece nos dados NÃO EXISTE: mencionar qualquer um
              deles é erro grave. Se um campo não veio nos dados, não fale dele.
            - Cada relíquia, ornamento, cone ou stat pertence SOMENTE ao personagem em
              cujo bloco ele aparece nos dados. NUNCA re-atribua um item de um personagem
              a outro, nem monte build para personagem que os dados só citam pelo nome.
            - RESPONDA AO QUE FOI PERGUNTADO, e só isso. Pergunta específica (a equipe,
              o elemento, um stat, um efeito) → resposta curta e direta com só essa parte
              dos dados, mesmo que tenha vindo mais coisa junto. Pergunta ampla (kit,
              build completa) → repasse TUDO o que veio nos dados sobre o que foi
              perguntado, sem cortar nem resumir.
            - O limite de "1 a 3 frases" NÃO se aplica a esta resposta: use o tamanho que
              a pergunta pedir, de uma linha a vários parágrafos.
            - DATA DE HOJE: ${LocalDate.now()}. Perguntas sobre "versão/patch/banner atual"
              ou "já lançou?" se resolvem comparando as datas que aparecem nos dados com a
              data de hoje (algo com data futura ainda NÃO está ativo).
            - NUNCA invente o efeito/descrição de um item. Se uma relíquia, ornamento,
              cone ou personagem aparece nos dados só pelo NOME, repasse só o nome, SEM
              acrescentar explicação ao lado. Quando o efeito real vier em outro bloco
              dos dados, inclua esse texto junto do nome — copiado fiel, não parafraseado
              de memória.
            - Os dados podem chegar em inglês ou chinês: TRADUZA tudo para português
              brasileiro. NUNCA copie trechos em outro idioma na resposta — nomes próprios
              de habilidades/cones podem ficar em inglês, mas descrições e efeitos devem
              estar 100% em PT-BR.
            - Resposta longa → organize com tópicos ("- " ou "• ") e títulos curtos em
              **negrito**. Resposta curta → texto corrido, sem tópicos. Markdown do
              Discord é suportado.
            - Mantenha um toque do seu jeito — um vocativo carinhoso no começo e/ou no fim —
              mas o corpo da resposta é informativo; afeto não substitui dado.
            - NÃO cite este bloco nem use rótulos meta ("contexto interno", "brain",
              "etapa", "base local", "web search"). Apenas entregue o conteúdo.
        """.trimIndent()

        // Appended LAST so it outranks the "repasse TUDO, sem cortar nem resumir" rule above —
        // that rule is right for a kit or a build, and exactly wrong when the user asked for a
        // resumo of several paragraphs of lore.
        val summaryRule = """
            ## Ajuste para ESTA pergunta

            O usuário pediu um RESUMO. Esta regra tem prioridade sobre a de "repassar tudo
            sem cortar": entregue de 3 a 6 frases, em texto corrido, cobrindo só os pontos
            principais. Resumir NÃO autoriza inventar: use apenas o que está nos dados, e
            prefira omitir a preencher lacuna.
        """.trimIndent()

        val systemBlock = listOfNotNull(
            extraSystemPrompt?.takeIf { it.isNotBlank() },
            instruction,
            summaryRule.takeIf { summarize },
        ).joinToString("\n\n")

        val question = history.lastOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()
        val payload = buildString {
            if (question.isNotEmpty()) append("Pergunta do usuário: ").append(question).append("\n\n")
            val how = if (summarize) "resuma os pontos principais" else "repasse TODOS os detalhes relevantes"
            append("Dados encontrados para responder ($how):\n")
            append(brainResult)
        }
        val syntheticTurn = ConversationMessage(
            conversationId = 0L,
            role = MessageRole.USER,
            content = payload,
        )
        val voiceMessages = promptBuilder.build(
            history = listOf(syntheticTurn),
            extraSystemPrompt = systemBlock,
            overrideOnly = false,
            userName = userName,
        )

        logPrompt("voice/knowledge", voiceMessages)

        val response = metrics.timePass("voice_knowledge") { chatModel.call(Prompt(voiceMessages, knowledgeVoiceOptions())) }
        val raw = response.result.output.text ?: ""
        return postProcessor.process(raw)
    }

    /**
     * Conversational voice call: persona + full history. Used when the brain produced no
     * action (NO_ACTION / DONE) — the voice just continues the chat naturally.
     */
    private fun runVoicePassConversational(
        history: List<ConversationMessage>,
        extraSystemPrompt: String?,
        userName: String?,
    ): String {
        val voiceMessages = promptBuilder.build(
            history = history,
            extraSystemPrompt = extraSystemPrompt?.takeIf { it.isNotBlank() },
            overrideOnly = false,
            userName = userName,
        )

        logPrompt("voice/conversational", voiceMessages)

        val response = metrics.timePass("voice_conversational") { chatModel.call(Prompt(voiceMessages, voiceOptions())) }
        val raw = response.result.output.text ?: ""
        return postProcessor.process(raw)
    }

    // -------------------- Public API: legacy single-pass -------------------- //

    /**
     * Single-pass chat WITHOUT tools. Used by `/contexto-do-canal` to summarize recent
     * channel history with the persona attached. Tool callbacks are intentionally omitted
     * so the summarizer cannot accidentally moderate.
     */
    fun chat(history: List<ConversationMessage>): String {
        val messages = promptBuilder.build(history)
        val response = metrics.timePass("legacy") { chatModel.call(Prompt(messages, legacyOptions())) }
        val raw = response.result.output.text ?: ""
        return postProcessor.process(raw)
    }

    // -------------------- Internals -------------------- //

    /**
     * Logs the full prompt being sent to the model — every message, untruncated, with its
     * role — so the exact text reaching the LLM is visible in the logs. Gated on DEBUG
     * (com.cyrene is at DEBUG by default); raise the level if you don't want prompt dumps.
     */
    private fun logPrompt(tag: String, messages: List<Message>) {
        if (!log.isDebugEnabled) return
        val rendered = messages.joinToString("\n") { m ->
            "  ── ${m.messageType} ──\n${m.text}"
        }
        log.debug("Prompt [{}] ({} mensagens):\n{}", tag, messages.size, rendered)
    }

    /**
     * Per-pass [OllamaOptions]. Spring AI replaces yaml defaults with whatever is set
     * here on a per-call basis, so the tuning knobs must be applied at every call site
     * (which is why they live in dedicated helpers rather than being scattered).
     *
     * Each pass has its own sampling profile:
     *  - [brainOptions]: tight (low temp + topP) so tool calls aren't hallucinated.
     *  - [intentGateOptions]: deterministic (temp=0) + tiny token budget — single-word
     *    classification.
     *  - [voiceOptions]: warmer for natural persona prose.
     *  - [legacyOptions]: untuned for the single-pass `/contexto-do-canal` summarizer.
     */
    private fun brainOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(properties.performance.brainTemperature)
            .topP(properties.performance.brainTopP)
            .numCtx(properties.performance.numCtx)
            // Knowledge budget, not the 512 chat default: an HSR extraction (a full kit, or
            // a fetched web page distilled into one) needs room. It's only a ceiling —
            // moderation replies still stop after a sentence, so this never slows them down.
            .numPredict(properties.performance.knowledgeNumPredict)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    private fun voiceOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.voiceModelName)
            .temperature(properties.performance.voiceTemperature)
            .numCtx(properties.performance.numCtx)
            .numPredict(properties.performance.numPredict)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    /** Greeting-line options: the BRAIN model (hot + obedient — see [greetingLine])
     *  with a one-line token budget. */
    private fun greetingOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(properties.performance.voiceTemperature)
            .numCtx(properties.performance.numCtx)
            .numPredict(48)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    /**
     * Voice options for the knowledge path: the BRAIN model, not the conversational
     * voice model. Deliberate (user decision 2026-07-09): the brain is already resident
     * during a knowledge question (gate, condense and the grounding judge run on it), so
     * the retell reuses a hot runner instead of loading the voice model — and the live
     * A/B showed the persona-tuned 4B remixing facts the 14b relays faithfully. The
     * conversational/focused passes keep [voiceOptions] (persona prose is where the
     * PT-BR voice model shines). Larger [BotProperties.Performance.knowledgeNumPredict]
     * budget (a kit retelling is long) and a temperature capped lower — this pass relays
     * facts, so favour fidelity over flourish, while still honouring a user who
     * configured an even lower voice temp.
     */
    private fun knowledgeVoiceOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(properties.performance.voiceTemperature.coerceAtMost(0.6))
            .numCtx(properties.performance.numCtx)
            .numPredict(properties.performance.knowledgeNumPredict)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    /**
     * Intent gate options: deterministic, JSON-constrained, tiny token budget.
     *
     * numCtx is deliberately the SAME full window as every other pass: `num_ctx` is a
     * load-time parameter for Ollama, so a different value here would spawn a separate
     * runner and force a model reload on every gate→voice alternation — far more expensive
     * than the KV memory a smaller window would save.
     */
    private fun intentGateOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(0.0)
            .format("json")
            .numCtx(properties.performance.numCtx)
            .numPredict(24)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    /**
     * Verifier options: brain model, deterministic, JSON-constrained verdict. numCtx is the
     * full window — the judge must read the whole source [context] (up to the web-fetch
     * budget) alongside the answer to check grounding.
     */
    private fun verifyOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(0.0)
            .format("json")
            .numCtx(properties.performance.numCtx)
            .numPredict(24)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    /**
     * Condense options: brain model, deterministic, JSON-constrained, small budget — the
     * output is a single rewritten question. Full numCtx for the same runner-reuse reason
     * as [intentGateOptions].
     */
    private fun condenseOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.brainModelName)
            .temperature(0.0)
            .format("json")
            .numCtx(properties.performance.numCtx)
            .numPredict(160)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    private fun legacyOptions(): OllamaOptions =
        OllamaOptions.builder()
            .model(properties.modelName)
            .numCtx(properties.performance.numCtx)
            .numPredict(properties.performance.numPredict)
            .numThread(properties.performance.numThread)
            .keepAlive(properties.performance.keepAlive)
            .build()

    internal companion object {

        private val JSON = com.fasterxml.jackson.databind.ObjectMapper()

        /**
         * Extracts a string [field] from the model's JSON output, or null when the output
         * isn't parseable JSON / lacks the field. The constrained passes run with Ollama's
         * `format: json`, but every caller keeps its old bare-word parsing as the fallback,
         * so a model that ignores the format (or truncates mid-JSON) degrades to exactly the
         * pre-JSON behaviour — the fail-open contracts are untouched.
         */
        private fun jsonField(raw: String, field: String): String? =
            runCatching { JSON.readTree(raw).get(field)?.asText() }.getOrNull()

        /**
         * Maps the intent gate's output — `{"intent":"kb|chat"}` under `format: json`,
         * or a bare word from a non-conforming model — to an [Intent]. Pure and
         * side-effect-free so the routing contract can be unit-tested without invoking the
         * model. Matching is prefix-based and case-insensitive (the model occasionally adds
         * trailing punctuation or whitespace despite the prompt), and ANY unrecognised
         * output defaults to [Intent.CHAT] — the safe fallback, since a wrongly-chatty reply
         * is a far cheaper miss than a confidently invented kit.
         */
        internal fun parseIntent(raw: String): Intent {
            val s = (jsonField(raw, "intent") ?: raw).trim().lowercase()
            return if (s.startsWith("kb")) Intent.KNOWLEDGE else Intent.CHAT
        }

        /**
         * Maps the grounding judge's output — `{"veredito":"sim|nao"}` under `format: json`,
         * or a bare word — to a pass/fail. Returns false ONLY on a clear negative verdict
         * ("nao"/"não"/"no"); any other or unrecognised output passes. Pure, so the fail-open
         * contract is unit-testable without a model. The asymmetry is deliberate: abstaining
         * requires the judge to actively reject, so an ambiguous verdict never silences an
         * answer the deterministic gate already grounded.
         */
        internal fun parseVerdict(raw: String): Boolean {
            val s = (jsonField(raw, "veredito") ?: raw).trim().lowercase().trimStart('"', '\'', '`', ' ')
            return !(s.startsWith("nao") || s.startsWith("não") || s.startsWith("no"))
        }

        /**
         * Guards the condense step's output — `{"pergunta":"..."}` under `format: json`, or
         * bare text — before it replaces the grounding query. The model occasionally answers
         * instead of rewriting, or pads with explanation — both show up as multi-line or
         * bloated output, so anything blank, multi-line, or much longer than a question
         * falls back to [fallback] (the original question). Pure, so the fail-open contract
         * is unit-testable without a model.
         */
        internal fun sanitizeCondensed(raw: String, fallback: String): String {
            // JSON-looking output that didn't parse (truncated mid-string) must not leak into
            // the query as literal braces — treat it as unusable rather than as bare text.
            val candidate = jsonField(raw, "pergunta")
                ?: raw.takeUnless { it.trimStart().startsWith("{") }.orEmpty()
            val s = candidate.trim().trim('"', '\'', '`').trim()
            if (s.isEmpty() || s.contains('\n') || s.length > 300) return fallback
            return s
        }

        /** Condense-question prompt: rewrite the last user turn as a standalone question. */
        val CONDENSE_INSTRUCTIONS = """
            Você reescreve perguntas. Dada uma conversa e a última pergunta do usuário,
            reescreva APENAS a última pergunta como uma pergunta autônoma e completa em
            PT-BR, resolvendo pronomes e referências ("ela", "dele", "esse cone",
            "e os Eidolons?") com os nomes citados antes na conversa.

            - NÃO responda a pergunta.
            - Se a pergunta já é autônoma, repita-a exatamente como veio.
            - Preserve pedidos de profundidade ("kit completo", "pesquisa na internet").
            - Ignore rótulos como "[nome]:" — eles indicam apenas quem falou.

            Responda APENAS com JSON neste formato, nada mais:
            {"pergunta": "<a pergunta reescrita, em uma linha>"}
        """.trimIndent()

        /** Strips a leading "[name]: " speaker tag that the mention path prepends to turns. */
        private val SPEAKER_PREFIX = Regex("^\\[[^\\]]*]:\\s*")

        private val CJK = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")

        /**
         * True when [text] contains ANY CJK character. A PT-BR answer about HSR never needs
         * one (skill/cone proper nouns are Latin-script), so a single Han/Kana/Hangul char
         * is a reliable signal the voice drifted into the source language. Pure.
         */
        internal fun hasCjk(text: String): Boolean = CJK.containsMatchIn(text)

        /**
         * Exact-match whitelist of obviously-conversational openers that need no LLM
         * classification. Kept deliberately tight: anything not listed here falls through to
         * the real gate, so the cost of being wrong is only a missed optimisation, never a
         * misroute.
         */
        private val OBVIOUS_CHAT = setOf(
            "oi", "ola", "olá", "oii", "oie", "opa", "eai", "e ai", "e aí", "salve",
            "bom dia", "boa tarde", "boa noite",
            "obrigado", "obrigada", "obg", "brigado", "brigada", "valeu", "vlw",
            "tudo bem", "tudo certo", "blz", "beleza",
            "tchau", "flw", "falou", "até mais", "ate mais",
            "kkk", "kkkk", "kkkkk", "haha", "hahaha", "rs",
        )

        /**
         * Pre-LLM heuristic for the intent gate. Returns [Intent.CHAT] for the small set of
         * unambiguously-conversational messages and null for everything else (so the LLM gate
         * still decides). Matches only after stripping a leading "[name]:" speaker tag and
         * trailing punctuation, so a real HSR question can never slip through it. Pure, so it
         * is unit-testable without a model.
         */
        internal fun fastPathIntent(lastUserMessage: String): Intent? {
            val s = SPEAKER_PREFIX.replace(lastUserMessage.trim(), "")
                .lowercase()
                .trim()
                .trimEnd('.', '!', '?', ',')
                .trim()
            if (s.isEmpty()) return Intent.CHAT
            return if (s in OBVIOUS_CHAT) Intent.CHAT else null
        }

        /** Splits normalized text into word tokens for the cue checks below. */
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private fun tokensOf(message: String): Set<String> =
            HsrCharacterService.normalize(message).split(NON_WORD).filterTo(mutableSetOf()) { it.isNotEmpty() }

        /**
         * Verbs asking the bot to DO something to the server. The bot no longer can — that's
         * what the moderation slash commands are for — so these carry no routing weight of
         * their own. They still matter here: "bane o <@123> que
         * ficou falando da build da acheron" contains a real mechanics cue AND a real
         * character, so without this check the gazetteer would fast-path it and answer a
         * ban request with Acheron's build. Deferring sends it to the LLM gate, which reads
         * it as chat.
         *
         * Broader than [commandHintFor]'s vocabulary on purpose: deferring a false positive
         * costs one LLM call, while naming the wrong command at someone costs credibility.
         */
        private val SERVER_ACTION_VERBS = setOf(
            "muta", "mutar", "mute", "silencia", "silenciar", "cala", "calar", "timeout",
            "castiga", "castigar", "desmuta", "desmutar", "libera", "liberar",
            "expulsa", "expulsar", "chuta", "chutar", "kick",
            "bane", "banir", "ban", "unban", "desbane", "desbanir",
            "avisa", "avisar", "adverte", "advertir",
            "limpa", "limpar", "apaga", "apagar", "purge", "clear",
            "slowmode", "lento",
        )

        // Verb groups for [commandHintFor]. Each is narrower than its [SERVER_ACTION_VERBS]
        // counterpart — only words that can't plausibly mean anything else in a Discord chat.
        // "cala" and "castiga" are deliberately absent: "cala a boca kkk" is banter, and
        // answering it with a timeout command would be exactly the joke-killing behaviour
        // the intent gate was reworked to avoid.
        private val HINT_MUTE_VERBS = setOf("muta", "mutar", "mute", "silencia", "silenciar", "timeout")
        private val HINT_UNMUTE_VERBS = setOf("desmuta", "desmutar")
        private val HINT_BAN_VERBS = setOf("bane", "banir", "ban", "banimento")
        private val HINT_UNBAN_VERBS = setOf("desbane", "desbanir", "unban")
        private val HINT_WARN_VERBS = setOf("avisa", "avisar", "adverte", "advertir", "advertencia", "aviso", "warn")
        private val HINT_KICK_VERBS = setOf("expulsa", "expulsar", "kick", "kicka", "kickar")
        private val HINT_PURGE_VERBS = setOf("limpa", "limpar", "apaga", "apagar", "purge", "clear")
        private val HINT_ROLE_VERBS = setOf(
            "da", "dar", "adiciona", "adicionar", "coloca", "colocar", "atribui", "atribuir",
            "tira", "tirar", "remove", "remover", "revoga", "revogar",
        )
        private val HINT_CREATE_VERBS = setOf("cria", "criar", "abre", "abrir")

        /** Objects that turn an everyday verb ("apaga", "tira") into a real server request. */
        private val HINT_PURGE_OBJECTS = setOf("mensagens", "mensagem", "chat", "conversa", "canal")
        private val HINT_ROLE_OBJECTS = setOf("cargo", "cargos", "role", "roles")
        private val HINT_CHANNEL_OBJECTS = setOf("canal", "canais")
        private val HINT_PERSON_OBJECTS = setOf("membro", "membros", "usuario", "usuarios", "pessoa")

        /**
         * Slowmode is asked for as a noun phrase ("modo lento", "slowmode"), not a verb, so
         * it gets its own check. "lento" alone is not enough — "tá lento hoje" is a complaint
         * about the bot, not a request to throttle the channel.
         */
        private val HINT_SLOWMODE_PHRASES = setOf("slowmode", "slow")

        /**
         * The slash command that does what [message] is asking for in prose, or null when
         * it isn't a server-action request. Lets [respond] answer "use `/mutar`" without an
         * LLM round-trip, instead of leaving the persona to improvise — which historically
         * meant either an apologetic refusal or, worse, claiming the action was done.
         *
         * Precision over recall, deliberately. The generic verbs ("apaga", "tira", "cria")
         * only fire when paired with an object that pins the meaning down, so "apaga essa
         * imagem da minha mente" and "dá uma olhada nisso" stay conversation. A miss just
         * falls through to the normal chat reply; a false positive interrupts a joke with a
         * command list. Pure, so the whole vocabulary is unit-testable without a model.
         */
        internal fun commandHintFor(message: String): String? {
            val t = tokensOf(SPEAKER_PREFIX.replace(message.trim(), ""))
            fun any(words: Set<String>) = t.any { it in words }
            return when {
                // Object-anchored first: "tira o cargo do fulano" is a role request, not a purge.
                any(HINT_ROLE_VERBS) && any(HINT_ROLE_OBJECTS) -> "cargo"
                any(HINT_CREATE_VERBS) && any(HINT_CHANNEL_OBJECTS) -> "criar-canal"
                any(HINT_PURGE_VERBS) && any(HINT_PURGE_OBJECTS) -> "limpar"
                any(HINT_SLOWMODE_PHRASES) || ("modo" in t && "lento" in t) -> "modo-lento"
                // These verbs mean one thing in a Discord server, so they stand alone.
                // The un- forms come first: "desmutar" contains no "mutar" token after
                // tokenisation, but keeping the pairs adjacent makes the precedence explicit.
                any(HINT_UNMUTE_VERBS) -> "desmutar"
                any(HINT_UNBAN_VERBS) -> "desbanir"
                any(HINT_MUTE_VERBS) -> "mutar"
                any(HINT_BAN_VERBS) -> "banir"
                any(HINT_KICK_VERBS) -> "expulsar"
                // "avisa" alone is everyday speech ("me avisa quando sair"), so it needs the
                // person to be named — a mention, or the word "membro"/"usuario".
                any(HINT_WARN_VERBS) && (message.contains("<@") || any(HINT_PERSON_OBJECTS)) -> "avisar"
                else -> null
            }
        }

        /**
         * Game-mechanics vocabulary that makes a character mention an unambiguous knowledge
         * question. Deliberately NO generic question words ("quem", "qual", "como") and NO
         * bare-'?' shape: "será que a Acheron me ama?" is a joke, not a kb question, and a
         * false positive here bypasses the LLM gate straight into a build dump — the exact
         * "joke killed by relic stats" failure. Ambiguous mentions defer to the LLM gate
         * (return null), which costs one small hot-model call, never the joke.
         */
        /**
         * Mechanics/data words that, TOGETHER WITH a named character, mean the user wants data.
         * Deliberately never enough on their own — see [gazetteerFastPath].
         *
         * The lore/field words ("historia", "descricao", "faccao", "memoespirito"…) are safe here
         * precisely because of that pairing: "me conta uma história" names nobody and still defers
         * to the LLM gate, while "me conta a história da himeko" is unambiguously a data request.
         * Without them the gate read those as storytelling and answered from the model's own
         * memory — which is exactly how a lore question came back invented.
         */
        private val KNOWLEDGE_CUES = setOf(
            "kit", "build", "builds", "cone", "cones", "eidolon", "eidolons",
            "reliquia", "reliquias", "time", "times", "sinergia", "elemento", "caminho",
            "habilidade", "habilidades", "talento", "tecnica", "ultimate", "ult",
            "banner", "lore", "farmar", "materiais",
            // lore + single-field asks (all backed by real columns)
            "historia", "historias", "descricao", "passado", "origem", "biografia",
            "faccao", "raridade", "estrelas", "ornamento", "ornamentos",
            "memoespirito", "memosprite", "euforia", "traco", "tracos", "passiva", "passivas",
        )

        internal fun hasKnowledgeShape(message: String): Boolean =
            tokensOf(message).any { it in KNOWLEDGE_CUES }

        /**
         * Gazetteer fast-path decision. Fires KNOWLEDGE only when the message carries an
         * explicit game-mechanics cue ([KNOWLEDGE_CUES]), no [SERVER_ACTION_VERBS], and
         * [mentionsCharacter] confirms a known HSR character name; anything else returns
         * null (defer to the LLM gate). A
         * bare question mark or generic question word is NOT enough — banter naming a
         * character must reach the LLM gate, which sees conversation context and can route
         * it to chat. The character lookup is a lambda so this stays pure and testable
         * without the DB-backed service.
         */
        internal fun gazetteerFastPath(
            message: String,
            mentionsCharacter: (String) -> Boolean,
            isTableQuestion: (String) -> Boolean = { false },
        ): Intent? {
            val s = SPEAKER_PREFIX.replace(message.trim(), "")
            // A request to act on the server is never a data question, however many game
            // words ride along with it — see [SERVER_ACTION_VERBS].
            if (tokensOf(s).any { it in SERVER_ACTION_VERBS }) return null
            // A table question names NO character by design ("quantas relíquias tem no total?",
            // "quantos membros tem na facção Expresso Astral?"), so the character pairing below
            // can never fire for one — it needs its own tier or it falls through to the LLM
            // gate, which reads it as small talk and deflects.
            if (isTableQuestion(s)) return Intent.KNOWLEDGE
            if (!hasKnowledgeShape(s)) return null
            return if (mentionsCharacter(s)) Intent.KNOWLEDGE else null
        }

        /**
         * User block for the gate prompt: the last user message plus up to two prior turns
         * as labelled context. Banter is context-dependent — "e a acheron?" mid-joke reads
         * nothing like the same words cold — and two truncated turns cost a few dozen
         * tokens on the hot brain model. Pure, so the shape is unit-testable.
         */
        internal fun gateUserBlock(history: List<ConversationMessage>, lastUser: String): String {
            val lastUserIdx = history.indexOfLast { it.role == MessageRole.USER }
            val prior = if (lastUserIdx <= 0) emptyList() else history.subList(0, lastUserIdx).takeLast(2)
            if (prior.isEmpty()) return "Mensagem: $lastUser\nResposta:"
            val context = prior.joinToString("\n") { m ->
                val who = if (m.role == MessageRole.USER) "Usuário" else "Bot"
                "$who: ${m.content.take(200)}"
            }
            return "Conversa anterior (apenas contexto — classifique SÓ a última mensagem):\n" +
                "$context\n\nMensagem: $lastUser\nResposta:"
        }

        /**
         * Intent gate system prompt. Forces a one-word PT-BR classification of the last user
         * turn: "kb" takes the grounded HSR pipeline, anything else falls back to "chat".
         * The bot has no Discord actions to route to — moderation is slash-commands only —
         * so a request to mute or ban someone is just conversation here, and the persona
         * answers it by pointing at the command.
         */
        val INTENT_GATE_INSTRUCTIONS = """
            Você é um classificador. Olhe a mensagem do usuário e responda APENAS com JSON
            neste formato: {"intent": "kb"} — onde o valor é "kb" ou "chat". Nada mais.

            Responda "kb" quando a mensagem PEDE DADOS do jogo Honkai: Star Rail (HSR):
            - kits, habilidades, elementos, caminhos (Paths), Eidolons, status
            - cones de luz (Light Cones), relíquias, builds, times, sinergias
            - lore, história, mecânicas, versão/patch atual, banners, eventos, materiais
            - recomendações que dependem desses dados: "qual o melhor cone pro Dan Heng?",
              "vale a pena puxar a Acheron?", "qual o melhor time pra ela?"
            - quem um personagem É: "quem é a Acheron?", "que elemento é o Jing Yuan?"

            Responda "chat" para QUALQUER outra coisa:
            - saudações, despedidas, agradecimentos, perguntas sobre o bot
            - elogios, declarações, flerte, brincadeiras, memes, papo aleatório
            - pedidos de história inventada, desabafo, conselho de vida
            - opiniões que NÃO dependem de fatos do jogo
            - pedidos de moderação do servidor (mutar, banir, expulsar, limpar mensagens,
              dar/tirar cargo, criar canal) — o bot NÃO faz isso por conversa, só por
              comando de barra, então isso é "chat"

            REGRA MAIS IMPORTANTE: citar um personagem NÃO é pedir dados. Piada, elogio,
            flerte ou papo que só MENCIONA um personagem é "chat" — só é "kb" quando a
            mensagem quer FATOS do jogo. Se a conversa anterior está em tom de brincadeira
            e a mensagem continua a brincadeira, é "chat".

            Exemplos:
            - "quem é a Acheron?" → {"intent": "kb"}
            - "qual o melhor cone pro Welt?" → {"intent": "kb"}
            - "a Acheron é boa? vale a pena puxar?" → {"intent": "kb"}
            - "a acheron é linda demais" → {"intent": "chat"}
            - "será que a Acheron me ama?" → {"intent": "chat"}
            - "imagina o Welt pagando boleto kkk" → {"intent": "chat"}
            - "casa comigo igual a Firefly casaria?" → {"intent": "chat"}
            - "muta o <@123> aí" → {"intent": "chat"}

            Não explique. Não comente. APENAS o JSON: {"intent": "kb"|"chat"}.
        """.trimIndent()

        /**
         * Grounding-judge prompt. Forces a single-word verdict on whether the answer is fully
         * supported by the provided source. Strict by design: anything in the answer that isn't
         * in the context counts as unsupported, so an embellished/invented stat fails.
         */
        val VERIFY_INSTRUCTIONS = """
            Você é um verificador de fidelidade. Recebe a PERGUNTA do usuário, um CONTEXTO
            (dados de fonte) e uma RESPOSTA. Sua tarefa: decidir se as afirmações factuais
            da RESPOSTA (nomes, elementos, caminhos, habilidades, números, efeitos) estão
            sustentadas pelo CONTEXTO.

            - Se a RESPOSTA CONTRADIZ o CONTEXTO ou INVENTA dados que não aparecem nele
              (habilidades, números, datas, personagens), o veredito é "nao".
            - Se as afirmações da RESPOSTA estão apoiadas no CONTEXTO, o veredito é "sim".
            - O CONTEXTO pode estar em INGLÊS ou outro idioma e a RESPOSTA em português:
              traduções e paráfrases fiéis CONTAM como sustentadas.
            - Nomes/termos que o usuário usou na PERGUNTA podem reaparecer na RESPOSTA mesmo
              que o CONTEXTO chame a mesma coisa de outro nome (apelido, nome vazado, nome
              localizado) — isso NÃO reprova.
            - A RESPOSTA pode cobrir só uma parte do CONTEXTO — omissão NÃO reprova.
            - Deduções DIRETAS do CONTEXTO contam como apoiadas — ex.: se o CONTEXTO diz que
              uma versão lança numa data posterior à DATA DE HOJE, afirmar que a versão atual
              é a anterior está sustentado.
            - Vocativos, saudações e tom carinhoso NÃO contam como afirmação factual — ignore-os.
            - Na DÚVIDA, responda "sim" — reprove apenas invenção ou contradição CLARA.

            Responda APENAS com JSON neste formato: {"veredito": "sim"} ou {"veredito": "nao"}.
            Nada mais.
        """.trimIndent()

        /**
         * Greeting pass for code-rendered (build/kit) answers: the persona writes ONE
         * opening line announcing the delivered answer; the factual body is appended by
         * code afterwards. The model is told only the SUBJECT, never the question or the
         * data (see [greetingLine] for why). Tone examples anchor the delivery shape —
         * the same "copy the tone, not the words" trick the persona file uses.
         */
        val GREETING_INSTRUCTIONS = """
            ## Sua tarefa agora

            O usuário fez uma pergunta técnica de Honkai: Star Rail e a resposta factual
            já está pronta — ela será colada logo abaixo da SUA linha. Escreva APENAS UMA
            linha curta de abertura em personagem entregando essa resposta.

            Regras:
            - A linha ANUNCIA que aqui está o que a pessoa pediu. NÃO faça pergunta de volta.
            - NÃO comente o jogo, NÃO dê opinião sobre personagem, NÃO invente dados.
            - Uma linha só, sem listas, sem markdown, sem aspas.

            Exemplos do tom (copie o tom, não as palavras):
            - "Fui lá buscar pra você, amor. Olha só:"
            - "Anotado e entregue, {nome} — do jeitinho que você pediu:"
            - "Isso eu tenho na ponta da língua. Segura:"
        """.trimIndent()

        /**
         * First non-blank line of the greeting output, unquoted, or null when it's
         * unusable: blank, list/heading-shaped, or longer than a one-liner has any
         * business being — the length cap is the tell for the observed failure mode
         * where the model runs past the opener into an invented answer on the SAME
         * line. Null → canned opener. Pure.
         */
        internal fun sanitizeGreeting(raw: String): String? =
            raw.lineSequence().map { it.trim().trim('"', '“', '”') }.firstOrNull(String::isNotEmpty)
                ?.takeIf { it.length <= 160 && !it.startsWith("-") && !it.startsWith("*") && !it.startsWith("#") }

    }
}
