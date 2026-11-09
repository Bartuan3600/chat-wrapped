package de.bartuan.chatwrapped

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {
    @Test fun storeRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val st = ChatStore(dir)
        val src = ChatSource("Ben", "17.10.26, 10:00 - Anna: Hi\n17.10.26, 10:01 - Ben: Hey\n", mapOf(MediaType.PHOTO to 2))
        val meta = st.save(src, ChatStats(Parser.parse(src.text)))
        assertEquals(1, st.list().size); assertEquals(2, st.list()[0].media[MediaType.PHOTO])
        st.saveVerdict(meta.id, "## Gesamtfazit\nok"); assertNotNull(st.verdict(meta.id))
        st.delete(meta.id); assertTrue(st.list().isEmpty()); assertNull(st.source(meta.id))
        dir.deleteRecursively()
    }
}
