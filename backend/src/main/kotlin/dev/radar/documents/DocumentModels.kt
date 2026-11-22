package dev.radar.documents

import dev.radar.research.ResearchMessage
import kotlinx.serialization.Serializable

@Serializable data class DocumentRecord(
    val id: String, val message: ResearchMessage, val fileName: String, val mimeType: String,
    val size: Long, val format: String, val state: String = "NOT_DOWNLOADED", val downloadedBytes: Long = 0,
    val error: String? = null, val retryAt: Long = 0, val hasFile: Boolean = false,
    val hasText: Boolean = false, val truncated: Boolean = false,
)
@Serializable data class DocumentList(val items: List<DocumentRecord>, val total: Int, val bytes: Long)
@Serializable data class DocumentHit(val document: DocumentRecord, val snippet: String)
@Serializable data class DocumentSearch(val items: List<DocumentHit>, val total: Int)
@Serializable data class DocumentDiscovery(val items: List<DocumentRecord>, val nextCursor: Long? = null, val notice: String? = null)
@Serializable data class DocumentDiscoverInput(val sourceKey: String, val before: Long = 0, val limit: Int = 50)
@Serializable data class DocumentRegisterInput(val sourceKey: String, val messageId: Long)
@Serializable data class DocumentText(val text: String, val truncated: Boolean)
internal data class StoredDocument(val record: DocumentRecord, val fileId: Int, val remoteId: String, val hash: String?, val text: String)
