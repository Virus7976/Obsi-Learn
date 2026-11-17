package app.obsilearn

import app.obsilearn.data.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    private val xml = """<?xml version="1.0"?>
<rss xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
<title>Podnews Weekly Review</title>
<itunes:image href="https://example.com/art.jpg" />
<item>
  <title>iHeart - the business deal that doesn&#39;t make any sense</title>
  <description><![CDATA[<p>Send James &amp; Sam a message</p>]]></description>
  <enclosure url="https://example.com/ep.mp3" length="1234" type="audio/mpeg" />
  <pubDate>Thu, 13 Nov 2025 06:00:00 -0500</pubDate>
  <itunes:duration>4740</itunes:duration>
  <podcast:transcript url="https://example.com/t" type="text/html" />
  <podcast:transcript url="https://example.com/t.json" type="application/json" />
  <podcast:transcript url="https://example.com/t.srt" type="application/x-subrip" />
  <podcast:transcript url="https://example.com/t.vtt" type="text/vtt" />
</item>
<item>
  <title>Second</title>
  <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" />
  <itunes:duration>1:02:03</itunes:duration>
  <podcast:transcript url="https://example.com/t2.json" type="application/json" />
</item>
</channel></rss>"""

    @Test fun readsEpisodeDetailsAndTheBestTranscript() {
        val p = Feed.parse(xml)
        assertEquals(2, p.items.size)
        val e = p.items[0]
        assertEquals("iHeart - the business deal that doesn't make any sense", e.title)
        assertEquals("https://example.com/t.vtt", e.transcript)
        assertEquals(4740, e.duration)
        assertTrue(e.published > 0)
        assertEquals("Send James & Sam a message", e.summary)
        assertEquals("https://example.com/t2.json", p.items[1].transcript)
        assertEquals(3723, p.items[1].duration)
        assertEquals("Podnews Weekly Review", p.title)
    }
}
