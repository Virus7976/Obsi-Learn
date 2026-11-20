package app.obsilearn

import app.obsilearn.data.PhoneSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSpeechTest {
    @Test fun wordsTakeTheirTokensTimes() {
        // Parakeet gives word pieces; a piece with a space in front starts a word.
        val w = PhoneSpeech.stretch(
            "Hello, Jake. Fungi heal.",
            arrayOf(" Hel", "lo", ",", " Ja", "ke", ".", " Fun", "gi", " heal", "."),
            floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.7f, 0.9f, 1.2f, 1.4f, 1.8f, 2.1f),
            10.0, 2.5,
        )
        assertEquals(listOf("Hello,", "Jake.", "Fungi", "heal."), w.map { it.text })
        assertEquals(listOf(10.0, 10.6, 11.2, 11.8), w.map { Math.round(it.start * 10) / 10.0 })
    }

    @Test fun theSpaceMarkWorksToo() {
        val w = PhoneSpeech.stretch("the cat", arrayOf("▁the", "▁c", "at"), floatArrayOf(0f, 0.5f, 0.7f), 0.0, 1.0)
        assertEquals(listOf(0.0, 0.5), w.map { Math.round(it.start * 10) / 10.0 })
    }

    @Test fun withNoTimesTheWordsAreSpreadInOrder() {
        // Moonshine gives no times: the words share the stretch, a longer word taking longer.
        val w = PhoneSpeech.stretch("A mushroom grows.", emptyArray(), FloatArray(0), 5.0, 4.0)
        assertEquals(listOf("A", "mushroom", "grows."), w.map { it.text })
        assertEquals(5.0, w[0].start, 1e-9)
        assertTrue(w.zipWithNext().all { (a, b) -> b.start > a.start })
        assertTrue(w.last().end <= 9.0 + 1e-9)
        // "mushroom" is long, so it takes more of the time than "A".
        assertTrue(w[1].end - w[1].start > w[0].end - w[0].start)
    }

    @Test fun silenceGivesNoWords() {
        assertTrue(PhoneSpeech.stretch("  ", emptyArray(), FloatArray(0), 0.0, 1.0).isEmpty())
    }
}
