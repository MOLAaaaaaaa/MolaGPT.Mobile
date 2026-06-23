package com.molagpt.app.core.network

import com.molagpt.app.core.model.Attachment
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ByokMessageContentBuilderTest {
    @Test
    fun anthropicContentUsesSendContentAndPdfDocumentBlock() {
        val content = ByokMessageContentBuilder.anthropicContent(pdfMessage())

        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("hidden attachment context", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        val document = content[1].jsonObject
        val source = document["source"]!!.jsonObject
        assertEquals("document", document["type"]?.jsonPrimitive?.content)
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("application/pdf", source["media_type"]?.jsonPrimitive?.content)
        assertEquals(PDF_BASE64, source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun geminiPartsUsesSendContentAndPdfInlineData() {
        val parts = ByokMessageContentBuilder.geminiParts(pdfMessage())

        assertEquals("hidden attachment context", parts[0].jsonObject["text"]?.jsonPrimitive?.content)

        val inlineData = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("application/pdf", inlineData["mimeType"]?.jsonPrimitive?.content)
        assertEquals(PDF_BASE64, inlineData["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun textAttachmentsAreNotDuplicatedAsBinaryParts() {
        val message = pdfMessage(
            attachments = listOf(
                Attachment(
                    id = "text-1",
                    name = "notes.txt",
                    mimeType = "text/plain",
                    remoteUrl = "data:text/plain;base64,aGVsbG8=",
                ),
            ),
        )

        assertEquals(1, ByokMessageContentBuilder.anthropicContent(message).size)
        assertEquals(1, ByokMessageContentBuilder.geminiParts(message).size)
    }

    private fun pdfMessage(
        attachments: List<Attachment> = listOf(
            Attachment(
                id = "pdf-1",
                name = "paper.pdf",
                mimeType = "application/pdf",
                remoteUrl = "data:application/pdf;base64,$PDF_BASE64",
            ),
        ),
    ) = ChatMessage(
        messageId = "m1",
        sessionId = "s1",
        role = Role.USER,
        status = MessageStatus.COMPLETE,
        createdAt = 1L,
        updatedAt = 1L,
        rawText = "visible message",
        attachments = attachments,
        metadata = mapOf("sendContent" to "hidden attachment context"),
    )

    private companion object {
        const val PDF_BASE64 = "JVBERi0xLjQ="
    }
}
