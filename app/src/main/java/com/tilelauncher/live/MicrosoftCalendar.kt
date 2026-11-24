package com.tilelauncher.live

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.text.format.DateUtils

/**
 * Microsoft apps don't share data with other apps, but Outlook syncs its calendars into
 * Android's calendar provider, and Teams meetings live there too (with a join link). That's
 * enough for Outlook to show an agenda and Teams to show the next meeting.
 */
object MicrosoftCalendar {

    val OUTLOOK = setOf("com.microsoft.office.outlook")
    val TEAMS = setOf("com.microsoft.teams", "com.microsoft.teams2")

    private val ACCOUNT_HINTS = listOf("outlook", "microsoft", "exchange", "office365", "hotmail", "live.com")
    private val TEAMS_HINTS = listOf("teams.microsoft.com", "microsoft teams", "teams.live.com")

    private class Event(val title: String, val begin: Long, val allDay: Boolean, val location: String?, val description: String?)

    fun faces(context: Context, whenText: (Long, Boolean) -> String): Map<String, List<TileFace>> {
        val microsoftCalendars = microsoftCalendarIds(context)
        val upcoming = upcoming(context)
        val agenda = upcoming
            .filter { (calendarId, _) -> calendarId in microsoftCalendars }
            .take(3)
            .map { (_, e) -> TileFace.Text(e.title, listOfNotNull(e.location, whenText(e.begin, e.allDay))) }
        val teams = upcoming
            .firstOrNull { (_, e) -> e.isTeamsMeeting() }
            ?.let { (_, e) -> listOf(TileFace.Text(e.title, listOf("teams meeting", whenText(e.begin, e.allDay)))) }
            .orEmpty()
        return buildMap {
            if (agenda.isNotEmpty()) OUTLOOK.forEach { put(it, agenda) }
            if (teams.isNotEmpty()) TEAMS.forEach { put(it, teams) }
        }
    }

    private fun Event.isTeamsMeeting(): Boolean {
        val haystack = listOfNotNull(title, location, description).joinToString(" ").lowercase()
        return TEAMS_HINTS.any { it in haystack }
    }

    private fun microsoftCalendarIds(context: Context): Set<Long> = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.OWNER_ACCOUNT),
            null, null, null,
        )?.use { c ->
            buildSet {
                while (c.moveToNext()) {
                    val text = "${c.getString(1).orEmpty()} ${c.getString(2).orEmpty()}".lowercase()
                    if (ACCOUNT_HINTS.any { it in text }) add(c.getLong(0))
                }
            }
        }
    }.getOrNull().orEmpty()

    /** Future (or ongoing all-day) events in the next week, as (calendarId, event), soonest first. */
    private fun upcoming(context: Context): List<Pair<Long, Event>> = runCatching {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + 7 * DateUtils.DAY_IN_MILLIS)
        }.build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext() && size < 50) {
                    val allDay = c.getInt(3) == 1
                    val begin = c.getLong(2)
                    if (!allDay && begin < now) continue
                    add(
                        c.getLong(0) to Event(
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                            begin = begin,
                            allDay = allDay,
                            location = c.getString(4)?.takeIf { it.isNotBlank() && !it.startsWith("http") },
                            description = c.getString(5),
                        )
                    )
                }
            }
        }
    }.getOrNull().orEmpty()
}
