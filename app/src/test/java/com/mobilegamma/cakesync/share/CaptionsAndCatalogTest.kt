package com.mobilegamma.cakesync.share

import android.net.Uri
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class CaptionsAndCatalogTest {

    private fun photo(order: String? = null) = Photo(
        mediaId = 1, uri = mock(Uri::class.java), displayName = "a.jpg", mimeType = "image/jpeg",
        takenAtMillis = 0, labels = "", score = 1f, isMatch = true, override = null, uploadedAtMillis = null,
        orderTag = order,
    )

    private val cake = Category("c", "Cake", "Cake", 0.6f, "Cakes", hashtags = "#homebaker cake, pune")

    @Test fun hashtagsAreNormalised() {
        assertEquals("#homebaker #cake #pune", Captions.normaliseHashtags(cake.hashtags))
    }

    @Test fun captionFillsPlaceholdersAndDropsEmptyLines() {
        val caption = Captions.build(listOf(photo()), cake, "Soni Bakes")
        assertTrue(caption.startsWith("Cake 🎂"))
        assertTrue("Soni Bakes" in caption)
        assertTrue(caption.endsWith("#homebaker #cake #pune"))
        assertFalse("{order}" in caption)   // no order: the line is dropped
    }

    @Test fun captionIncludesOrder() {
        val caption = Captions.build(listOf(photo("Order 12 - Priya")), cake, "")
        assertTrue("Order 12 - Priya" in caption)
    }

    @Test fun catalogCsvEscapesAndHasMetaColumns() {
        val csv = Catalog.toCsv(
            listOf(Catalog.Item("id1", "Cake, chocolate", "Made \"fresh\"", Catalog.driveImageLink("F1"), "1200.00 INR"))
        )
        val lines = csv.trim().lines()
        assertEquals("id,title,description,availability,condition,price,link,image_link,brand", lines[0])
        assertTrue(lines[1].startsWith("id1,\"Cake, chocolate\",\"Made \"\"fresh\"\"\",in stock,new,1200.00 INR,"))
        assertTrue(lines[1].contains("https://drive.google.com/uc?export=view&id=F1"))
    }
}
