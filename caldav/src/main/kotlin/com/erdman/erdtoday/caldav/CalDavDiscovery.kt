package com.erdman.erdtoday.caldav

import at.bitfire.dav4jvm.XmlUtils.insertTag
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

object CalDavDiscovery {

    private const val FASTMAIL_CALDAV_HOST = "caldav.fastmail.com"

    suspend fun discoverOrCreateTaskCollection(httpClient: HttpClient): Result<Url> = runCatching {
        val wellKnownUrl = Url("https://$FASTMAIL_CALDAV_HOST/.well-known/caldav")
        val principal = getCurrentUserPrincipal(httpClient, wellKnownUrl)
            ?: error("Could not discover current-user-principal")
        val homeSet = getCalendarHomeSet(httpClient, principal)
            ?: error("Could not discover calendar-home-set")
        findVTodoCollection(httpClient, homeSet)
            ?: createVTodoCollection(httpClient, homeSet)
    }

    private suspend fun getCurrentUserPrincipal(httpClient: HttpClient, url: Url): Url? {
        var principal: Url? = null
        DavResource(httpClient, url).propfind(0, WebDAV.CurrentUserPrincipal)
            .filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .firstOrNull()
            ?.let { item ->
                // MultiStatusItem.Response wraps the actual Response -- properties/get() live on
                // .response, not on the wrapper itself.
                item.response.get(CurrentUserPrincipal::class.java)?.href?.let { href ->
                    principal = item.response.requestedUrl.resolve(href)
                }
            }
        return principal
    }

    private suspend fun getCalendarHomeSet(httpClient: HttpClient, principal: Url): Url? {
        var homeSet: Url? = null
        DavResource(httpClient, principal).propfind(0, CalDAV.CalendarHomeSet)
            .filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .firstOrNull()
            ?.let { item ->
                item.response.get(at.bitfire.dav4jvm.property.caldav.CalendarHomeSet::class.java)
                    ?.hrefs?.firstOrNull()
                    ?.let { href -> homeSet = item.response.requestedUrl.resolve(href) }
            }
        return homeSet
    }

    private suspend fun findVTodoCollection(httpClient: HttpClient, homeSet: Url): Url? {
        var found: Url? = null
        DavResource(httpClient, homeSet).propfind(
            1,
            WebDAV.ResourceType,
            CalDAV.SupportedCalendarComponentSet,
        ).filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .collect { item ->
                // SupportedCalendarComponentSet is a flat data class with three booleans --
                // no nested Comp list to walk (verified directly against dav4jvm source).
                val supportsVTodo = item.response
                    .get(at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet::class.java)
                    ?.supportsTasks
                if (supportsVTodo == true && found == null) found = item.response.requestedUrl
            }
        return found
    }

    private suspend fun createVTodoCollection(httpClient: HttpClient, homeSet: Url): Url {
        val folderName = UUID.randomUUID().toString()
        val collectionUrl = homeSet.let { Url("$it$folderName/") }
        val xmlBody = buildMkCalendarXml()
        // mkCol's trailing callback runs only on success -- a non-2xx status throws inside
        // DavResource before it's ever invoked, so there's nothing to do here but satisfy the
        // (non-defaulted) parameter.
        DavResource(httpClient, collectionUrl).mkCol(xmlBody = xmlBody, methodName = "MKCALENDAR") {}
        return collectionUrl
    }

    /**
     * Builds the MKCALENDAR request body for a VTODO-only collection named "ErdToday".
     * Adapted from DAVx5's real generateMkColXml (DavCollectionRepository.kt:354-463) --
     * trimmed to ErdToday's fixed case (always a calendar, never an address book; VTODO
     * only, no VEVENT/VJOURNAL; no color, no timezone, no description) rather than carrying
     * over parameters this app will never vary.
     */
    private fun buildMkCalendarXml(): String {
        val writer = java.io.StringWriter()
        val serializer = at.bitfire.dav4jvm.XmlUtils.newSerializer()
        serializer.apply {
            setOutput(writer)
            startDocument("UTF-8", null)
            setPrefix("", WebDAV.NS_WEBDAV)
            setPrefix("CAL", CalDAV.NS_CALDAV)
            startTag(CalDAV.NS_CALDAV, "mkcalendar")
            insertTag(WebDAV.Set) {
                insertTag(WebDAV.Prop) {
                    insertTag(WebDAV.ResourceType) {
                        insertTag(WebDAV.Collection)
                        insertTag(CalDAV.Calendar)
                    }
                    insertTag(WebDAV.DisplayName) { text("ErdToday") }
                    insertTag(CalDAV.SupportedCalendarComponentSet) {
                        insertTag(CalDAV.Comp) {
                            attribute(null, "name", "VTODO")
                        }
                    }
                }
            }
            endTag(CalDAV.NS_CALDAV, "mkcalendar")
            endDocument()
        }
        return writer.toString()
    }
}
