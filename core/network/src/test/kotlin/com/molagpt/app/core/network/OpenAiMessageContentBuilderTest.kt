package com.molagpt.app.core.network

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OpenAiMessageContentBuilderTest {
    @Test
    fun byokOpenAiCompatibleIncludesPdfFilePartWhenEnabled() {
        val wire = OpenAiMessageContentBuilder.build(pdfMessage(), includeFileParts = true)
        val content = wire.content as JsonArray

        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("hidden attachment context", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        val filePart = content[1].jsonObject
        val file = filePart["file"]!!.jsonObject
        assertEquals("file", filePart["type"]?.jsonPrimitive?.content)
        assertEquals("paper.pdf", file["filename"]?.jsonPrimitive?.content)
        assertEquals("data:application/pdf;base64,$PDF_BASE64", file["file_data"]?.jsonPrimitive?.content)
    }

    @Test
    fun defaultMolaGptPathDoesNotAttachPdfFilePart() {
        val wire = OpenAiMessageContentBuilder.build(pdfMessage())

        assertTrue(wire.content.jsonPrimitive.content.contains("hidden attachment context"))
    }

    @Test
    fun imageIsSentAsImageUrlByDefault() {
        val wire = OpenAiMessageContentBuilder.build(imageMessage("cat.jpg", "https://example.com/cat.jpg"))
        val content = wire.content as JsonArray

        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("look", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        assertEquals("image_url", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        val imageUrl = content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        assertEquals("https://example.com/cat.jpg", imageUrl)
    }

    @Test
    fun imageIsReplacedWithPlaceholderWhenRequested() {
        val wire = OpenAiMessageContentBuilder.build(
            imageMessage("cat.jpg", "https://example.com/cat.jpg"),
            replaceImagesWithText = true,
        )
        val content = wire.content as JsonArray

        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("look", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        assertEquals("text", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("[图片#1: cat.jpg]", content[1].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun imageOrdinalIncrementsGloballyAcrossMessages() {
        val ordinal = AtomicInteger(0)
        val first = OpenAiMessageContentBuilder.build(
            imageMessage("a.jpg", "https://example.com/a.jpg"),
            replaceImagesWithText = true,
            imageOrdinal = ordinal,
        )
        val second = OpenAiMessageContentBuilder.build(
            imageMessage("b.jpg", "https://example.com/b.jpg"),
            replaceImagesWithText = true,
            imageOrdinal = ordinal,
        )

        val firstContent = first.content as JsonArray
        val secondContent = second.content as JsonArray
        assertEquals("[图片#1: a.jpg]", firstContent[1].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("[图片#2: b.jpg]", secondContent[1].jsonObject["text"]?.jsonPrimitive?.content)
    }

    private fun pdfMessage() = ChatMessage(
        messageId = "m1",
        sessionId = "s1",
        role = Role.USER,
        status = MessageStatus.COMPLETE,
        createdAt = 1L,
        updatedAt = 1L,
        rawText = "visible message",
        attachments = listOf(
            Attachment(
                id = "pdf-1",
                name = "paper.pdf",
                mimeType = "application/pdf",
                remoteUrl = "data:application/pdf;base64,$PDF_BASE64",
            ),
        ),
        metadata = mapOf("sendContent" to "hidden attachment context"),
    )

    private fun imageMessage(name: String, url: String) = ChatMessage(
        messageId = "m1",
        sessionId = "s1",
        role = Role.USER,
        status = MessageStatus.COMPLETE,
        createdAt = 1L,
        updatedAt = 1L,
        rawText = "look",
        attachments = listOf(
            Attachment(
                id = "img-1",
                name = name,
                mimeType = "image/jpeg",
                remoteUrl = url,
            ),
        ),
    )

    private companion object {
        const val PDF_BASE64 = "JVBERi0xLjQ="
    }
}
