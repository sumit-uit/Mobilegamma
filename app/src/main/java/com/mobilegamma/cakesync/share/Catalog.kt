package com.mobilegamma.cakesync.share

/**
 * Builds a product catalog in Meta's bulk-upload CSV format (Commerce Manager → Catalog →
 * Data sources → Data feed). The same catalog feeds Facebook/Instagram Shops and the
 * WhatsApp Business catalog once connected there. Images must be public URLs, so items
 * point at their Google Drive copies.
 */
object Catalog {

    data class Item(
        val id: String,
        val title: String,
        val description: String,
        val imageLink: String,
        /** e.g. "1200.00 INR"; blank to fill in later. */
        val price: String = "",
        val availability: String = "in stock",
        val condition: String = "new",
        val brand: String = "",
        val link: String = imageLink,
    )

    val COLUMNS = listOf("id", "title", "description", "availability", "condition", "price", "link", "image_link", "brand")

    fun toCsv(items: List<Item>): String = buildString {
        appendLine(COLUMNS.joinToString(","))
        items.forEach { item ->
            val row = listOf(
                item.id, item.title, item.description, item.availability, item.condition,
                item.price, item.link, item.imageLink, item.brand,
            )
            appendLine(row.joinToString(",") { escape(it) })
        }
    }

    /** Direct image URL for a Drive file shared with "anyone with the link". */
    fun driveImageLink(fileId: String) = "https://drive.google.com/uc?export=view&id=$fileId"

    fun driveFolderLink(folderId: String) = "https://drive.google.com/drive/folders/$folderId"

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value
}
