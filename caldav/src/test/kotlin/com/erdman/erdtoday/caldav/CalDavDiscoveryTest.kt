package com.erdman.erdtoday.caldav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalDavDiscoveryTest {

    @Test
    fun `buildMkCalendarXml wraps the request in a CAL colon mkcalendar root element`() {
        val xml = CalDavDiscovery.buildMkCalendarXml()

        assertTrue(xml.contains("<CAL:mkcalendar"))
        assertTrue(xml.contains("</CAL:mkcalendar>"))
    }

    @Test
    fun `buildMkCalendarXml sets the displayname to ErdToday`() {
        val xml = CalDavDiscovery.buildMkCalendarXml()

        assertTrue(xml.contains("<displayname>ErdToday</displayname>"))
    }

    @Test
    fun `buildMkCalendarXml declares a VTODO-only supported-calendar-component-set`() {
        val xml = CalDavDiscovery.buildMkCalendarXml()

        assertTrue(xml.contains("<CAL:supported-calendar-component-set>"))
        assertTrue(xml.contains("<CAL:comp name=\"VTODO\""))
        // Not VEVENT or VJOURNAL -- this collection is VTODO-only.
        assertFalse(xml.contains("VEVENT"))
        assertFalse(xml.contains("VJOURNAL"))
    }

    @Test
    fun `buildMkCalendarXml does not set the protected resourcetype property`() {
        // Under MKCALENDAR (RFC 4791 S5.3.1) the resource type is implied by the method itself,
        // and resourcetype is a protected property -- a strict server can 403 a request that
        // tries to set it explicitly. See the comment on buildMkCalendarXml for the full reasoning.
        val xml = CalDavDiscovery.buildMkCalendarXml()

        assertFalse(xml.contains("resourcetype"))
    }
}
