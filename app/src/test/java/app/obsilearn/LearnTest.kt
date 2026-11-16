package app.obsilearn

import app.obsilearn.data.Entry
import app.obsilearn.data.Feed
import app.obsilearn.data.Layout
import app.obsilearn.data.ProgressNotes
import app.obsilearn.data.SavedVideo
import app.obsilearn.data.Source
import app.obsilearn.data.VideoMeta
import app.obsilearn.data.WebDav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val src = Source("s1", "folder", "My courses", "tree")
    private fun e(vararg path: String) = Entry(path.toList(), "u:" + path.joinToString("/"), 100)

    @Test fun imperiumStyleCourseWithNumberedFiles() {
        // The same layout as the Imperium course: module folders, files sharing a number are one lesson.
        val files = listOf(
            e("Imperium VIP", "01. Foundations", "01. Welcome to Imperium.mp4"),
            e("Imperium VIP", "01. Foundations", "01. Welcome to Imperium.vtt"),
            e("Imperium VIP", "01. Foundations", "01. Welcome to Imperium.html"),
            e("Imperium VIP", "01. Foundations", "01. (PDF) Welcome Guide.pdf"),
            e("Imperium VIP", "01. Foundations", "02. Setting Your Vision.mp4"),
            e("Imperium VIP", "01. Foundations", "10. Late lesson.mp4"),
            e("Imperium VIP", "02. Client Acquisition", "01. Finding Leads.mp4"),
            e("Imperium VIP", "02. Client Acquisition", "02. The Sales Call.mp4"),
            e("Imperium VIP", "02. Client Acquisition", "02. The Sales Call.html"),
        )
        val courses = Layout.courses(src, files)
        assertEquals(1, courses.size)
        val c = courses.single()
        assertEquals("Imperium VIP", c.title)
        assertEquals(listOf("Foundations", "Client Acquisition"), c.modules.map { it.title })
        val first = c.modules[0].lessons
        assertEquals(listOf("Welcome to Imperium", "Setting Your Vision", "Late lesson"), first.map { it.title })
        assertEquals("u:Imperium VIP/01. Foundations/01. Welcome to Imperium.vtt", first[0].subs)
        assertTrue(first[0].page.endsWith(".html"))
        assertEquals(listOf("Welcome Guide"), first[0].resources.map { it.title })
        assertEquals("", first[1].subs)
        assertTrue(c.modules[1].lessons[1].page.endsWith("The Sales Call.html"))
    }

    @Test fun aFolderOfVideosIsOneCourseWithOneModule() {
        val files = listOf(e("b.mp4"), e("a.mp4"), e("c.mp3"), e("cover.jpg"))
        val c = Layout.courses(src, files).single()
        assertEquals("My courses", c.title)
        assertEquals(1, c.modules.size)
        assertEquals(listOf("a", "b", "c"), c.lessons.map { it.title })
        assertEquals("audio", c.lessons[2].kind)
        assertEquals("u:cover.jpg", c.cover)
    }

    @Test fun aFolderOfCoursesGivesOneCourseEach() {
        val files = listOf(
            e("Spanish", "Week 1", "Lesson 1.mp4"), e("Spanish", "Week 2", "Lesson 1.mp4"),
            e("Guitar", "Basics", "1 Chords.mp4"), e("Guitar", "Basics", "2 Strumming.mp4"),
        )
        val courses = Layout.courses(src, files)
        assertEquals(listOf("Guitar", "Spanish"), courses.map { it.title })
        assertEquals(listOf("Week 1", "Week 2"), courses[1].modules.map { it.title })
        // Two lessons in two weeks with the same file name are still two lessons.
        assertEquals(2, courses[1].lessons.map { it.id }.toSet().size)
    }

    @Test fun allOneCourseKeepsDeepFoldersAsModules() {
        val files = listOf(e("Part A", "Unit 1", "x.mp4"), e("Part B", "Unit 1", "y.mp4"))
        val c = Layout.courses(src.copy(oneCourse = true), files).single()
        assertEquals(listOf("Part A / Unit 1", "Part B / Unit 1"), c.modules.map { it.title })
    }

    @Test fun idsStayTheSameOnARescan() {
        val a = Layout.courses(src, listOf(e("x", "1. a.mp4"))).single().lessons.single().id
        val b = Layout.courses(src, listOf(e("x", "1. a.mp4"), e("x", "2. b.mp4"))).single().lessons.first().id
        assertEquals(a, b)
    }

    @Test fun naturalOrderCountsLikeAPerson() {
        assertEquals(listOf("2 b", "10 a"), listOf("10 a", "2 b").sortedWith(Layout.natural))
    }

    @Test fun titlesAreCleaned() {
        assertEquals("Six Figure Fusion - Week 1 Business Genesis (START)", Layout.title("3. 1.3._Six_Figure_Fusion_-_Week_1_Business_Genesis_(START)_·_Imperium_Academy"))
        assertEquals("Welcome Guide", Layout.title("01. (PDF) Welcome Guide"))
    }
}

class ProgressNotesTest {
    private val src = Source("s1", "folder", "C", "tree")
    private val course = Layout.courses(src, listOf(
        Entry(listOf("M1", "1. Intro.mp4"), "a"), Entry(listOf("M1", "2. Next.mp4"), "b"), Entry(listOf("M2", "1. Last.mp4"), "c"),
    )).single()
    private fun v(id: String, done: Boolean = false, pos: Double = 0.0, at: Long = 0) =
        SavedVideo(VideoMeta(id, "t", "C"), positionSec = pos, playedAt = at, doneAt = if (done) 5 else 0)

    @Test fun noteHasFilterableFrontmatterAndOneLinePerLesson() {
        val l = course.lessons
        val text = ProgressNotes.render(course, mapOf(l[0].id to v(l[0].id, done = true), l[1].id to v(l[1].id, pos = 125.0, at = 99)), "", 0)
        assertTrue(text.startsWith("---\ntype: obsilearn-progress\nobsilearn: ${course.id}\n"))
        assertTrue(text.contains("  - obsilearn/progress"))
        assertTrue(text.contains("progress: 33"))
        assertTrue(text.contains("?t=125"))
        val marks = ProgressNotes.parse(text)
        assertEquals(3, marks.size)
        assertTrue(marks.getValue(l[0].id).done)
        assertEquals(125.0, marks.getValue(l[1].id).pos, 0.0)
        assertEquals(99L, marks.getValue(l[1].id).at)
    }

    @Test fun yourOwnNotesUnderTheLineStay() {
        val first = ProgressNotes.render(course, emptyMap(), "", 0)
        val mine = first + "\nMy thoughts on this course.\n"
        val again = ProgressNotes.render(course, emptyMap(), ProgressNotes.tail(mine), 0)
        assertTrue(again.trimEnd().endsWith("My thoughts on this course."))
    }

    @Test fun tickedInObsidianCountsAsDoneAndNewerPlaceWins() {
        val l = course.lessons
        val text = ProgressNotes.render(course, mapOf(l[1].id to v(l[1].id, pos = 300.0, at = 200)), "", 0)
            .replace("- [ ] [Intro]", "- [x] [Intro]")
        val here = mapOf(l[0].id to v(l[0].id), l[1].id to v(l[1].id, pos = 50.0, at = 100), l[2].id to v(l[2].id, pos = 10.0, at = 500))
        val m = ProgressNotes.merge(ProgressNotes.parse(text), here)
        assertTrue(m.getValue(l[0].id).done)
        assertEquals(300.0, m.getValue(l[1].id).pos, 0.0)
        // This phone played lesson 3 later than the note says, so the note does not move it back.
        assertFalse(l[2].id in m)
    }

    @Test fun doneHereIsNeverUndoneByAnOldNote() {
        val l = course.lessons
        val text = ProgressNotes.render(course, emptyMap(), "", 0)
        val m = ProgressNotes.merge(ProgressNotes.parse(text), mapOf(l[0].id to v(l[0].id, done = true)))
        assertFalse(l[0].id in m)
    }
}

class SourcesParseTest {
    @Test fun webDavAnswer() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/dav/Courses/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            <D:response><D:href>/dav/Courses/Week%201/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            <D:response><D:href>/dav/Courses/intro.mp4</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>12345</D:getcontentlength></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        val items = WebDav.parse(xml)
        assertEquals(3, items.size)
        assertTrue(items[1].dir)
        assertEquals(12345L, items[2].size)
        assertFalse(items[2].dir)
    }

    @Test fun podcastFeed() {
        val xml = """<rss><channel><title>My Course</title><itunes:image href="https://x/cover.jpg"/>
            <item><title>Part 2</title><enclosure url="https://x/2.mp3" type="audio/mpeg" length="20"/></item>
            <item><title><![CDATA[Part 1]]></title><enclosure url="https://x/1.mp4" type="video/mp4"/></item>
            <item><title>No file</title></item></channel></rss>"""
        val p = Feed.parse(xml)
        assertEquals("My Course", p.title)
        assertEquals("https://x/cover.jpg", p.image)
        assertEquals(listOf("Part 2", "Part 1"), p.items.map { it.title })
        assertEquals(20L, p.items[0].size)
    }
}


class SourceErrorsTest {
    @Test fun anHttpsAddressToAPlainServerIsRetriedAsHttp() {
        val e = javax.net.ssl.SSLException("Unable to parse TLS packet header")
        org.junit.Assert.assertEquals("http://192.168.50.137:8081/dav", app.obsilearn.data.SourceErrors.plainHttpFor("https://192.168.50.137:8081/dav", e))
        org.junit.Assert.assertNull(app.obsilearn.data.SourceErrors.plainHttpFor("http://x", e))
        org.junit.Assert.assertNull(app.obsilearn.data.SourceErrors.plainHttpFor("https://x", java.io.IOException("reset")))
        org.junit.Assert.assertEquals("http://x:8080/a", app.obsilearn.data.SourceErrors.plainHttpFor("https://x:8080/a", java.net.SocketTimeoutException("Read timed out")))
    }

    @Test fun anSmbAddressInTheWebDavFormBecomesAShare() {
        val s = app.obsilearn.data.Source("webdav-1", "webdav", "NAS", "smb://192.168.50.137/Media", "jake", "pw")
        assertEquals("smb", app.obsilearn.data.SourceErrors.kindFor(s).kind)
        assertEquals("webdav", app.obsilearn.data.SourceErrors.kindFor(s.copy(kind = "smb", uri = "http://nas:8080/Media")).kind)
        assertEquals("webdav", app.obsilearn.data.SourceErrors.kindFor(s.copy(uri = "http://nas:8080/Media")).kind)
    }

    @Test fun errorsAreSaidInPlainWords() {
        assertTrue(app.obsilearn.data.SourceErrors.say(java.net.ConnectException("failed")).startsWith("Nothing answered"))
        assertTrue(app.obsilearn.data.SourceErrors.say(java.io.IOException("x", javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."))).contains("not trusted"))
    }
}
