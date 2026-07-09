package com.cyrene.discord

import net.dv8tion.jda.api.entities.Activity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parser for the `.bot.activity` line that `bot.sh activity ...` writes. Covers each tipo, the
 * no-tipo back-compat path (whole line = "Playing …"), and the clear cases (empty / tipo-only).
 */
class ActivityStatusWatcherTest {

    private fun parse(s: String) = ActivityStatusWatcher.parseActivity(s)

    @Test
    fun `each tipo maps to its ActivityType with the tipo stripped`() {
        assertEquals(Activity.ActivityType.PLAYING, parse("playing Honkai: Star Rail!")?.type)
        assertEquals("Honkai: Star Rail!", parse("playing Honkai: Star Rail!")?.name)
        assertEquals(Activity.ActivityType.WATCHING, parse("watching um anime")?.type)
        assertEquals(Activity.ActivityType.LISTENING, parse("listening música")?.type)
        assertEquals(Activity.ActivityType.COMPETING, parse("competing um torneio")?.type)
        assertEquals(Activity.ActivityType.CUSTOM_STATUS, parse("custom texto livre")?.type)
        assertEquals("texto livre", parse("custom texto livre")?.name)
    }

    @Test
    fun `tipo keyword is case-insensitive`() {
        assertEquals(Activity.ActivityType.WATCHING, parse("WATCHING algo")?.type)
    }

    @Test
    fun `streaming pulls the url off the front and keeps the rest as text`() {
        val a = parse("streaming https://twitch.tv/foo meu stream ao vivo")
        assertEquals("meu stream ao vivo", a?.name)
        assertEquals("https://twitch.tv/foo", a?.url)
    }

    @Test
    fun `no tipo keeps the whole line as a Playing activity`() {
        val a = parse("Honkai: Star Rail!")
        assertEquals(Activity.ActivityType.PLAYING, a?.type)
        assertEquals("Honkai: Star Rail!", a?.name)
    }

    @Test
    fun `empty file and tipo-with-no-text both clear the activity`() {
        assertNull(parse(""))
        assertNull(parse("   \n "))
        assertNull(parse("playing"))
        assertNull(parse("streaming https://twitch.tv/foo")) // url but no text
    }
}
