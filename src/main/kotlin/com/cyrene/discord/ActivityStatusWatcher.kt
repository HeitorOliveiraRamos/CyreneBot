package com.cyrene.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Lets `bot.sh activity <tipo> "..."` change the bot's Discord activity WITHOUT a restart.
 *
 * The script writes one line to [FILE] (the bot's working dir); this bean polls the file and,
 * only when it changed, pushes it to the gateway. Line format: `<tipo> <texto>`, where tipo is
 * one of playing|watching|listening|competing|custom, plus `streaming <url> <texto>`. An
 * unknown/absent tipo means the whole line is a plain "Playing …" (back-compat with the old
 * `activity "texto"` form). Empty file = no activity. Read on startup too, so the activity
 * survives `bot.sh restart` for free.
 *
 * ponytail: file-poll IPC instead of an HTTP/actuator endpoint — no web starter to pull in,
 * and the script and JVM never share memory.
 */
@Component
class ActivityStatusWatcher(private val jda: JDA) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var last: String = ""

    @Scheduled(initialDelay = 5, fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    fun poll() {
        val raw = runCatching { if (Files.exists(FILE)) Files.readString(FILE) else "" }
            .getOrElse { return } // unreadable/half-written: skip this tick, keep current
        if (raw == last) return
        last = raw
        val activity = parseActivity(raw)
        jda.presence.setActivity(activity)
        log.info("Discord activity updated: {}", activity?.let { "${it.type} ${it.name}" } ?: "(cleared)")
    }

    companion object {
        private val FILE: Path = Path.of(".bot.activity")
        private val WS = Regex("\\s+")

        /** Parse one `.bot.activity` line into a JDA [Activity]; null = clear the activity. */
        fun parseActivity(raw: String): Activity? {
            val line = raw.trim()
            if (line.isEmpty()) return null
            val (head, tail) = line.split(WS, limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
            return when (head.lowercase()) {
                "playing" -> tail.ifEmpty { return null }.let(Activity::playing)
                "watching" -> tail.ifEmpty { return null }.let(Activity::watching)
                "listening" -> tail.ifEmpty { return null }.let(Activity::listening)
                "competing" -> tail.ifEmpty { return null }.let(Activity::competing)
                "custom" -> tail.ifEmpty { return null }.let(Activity::customStatus)
                "streaming" -> {
                    // `streaming <url> <texto>` — url is a single token; an invalid url makes
                    // JDA silently downgrade to PLAYING (no throw), so no validation needed here.
                    val (url, text) = tail.split(WS, limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
                    if (text.isEmpty()) return null
                    Activity.streaming(text, url)
                }
                else -> Activity.playing(line) // no tipo → whole line is "Playing …"
            }
        }
    }
}
