package dev.radar.documents

import dev.radar.research.*
import dev.radar.sources.SourceReadService
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.drinkless.tdlib.TdApi
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Explicit owner-local downloads. One worker, no automatic resume and no second TDLib client. */
class DocumentService(
    private val repo: Repositories,
    private val reader: SourceReadService,
    documentsRoot: Path,
    private val tdlibRoot: Path,
    private val execute: suspend (TdApi.Function<*>) -> TdApi.Object,
    private val extractor: DocumentExtractor = DocumentExtractor(),
    private val pollMillis: Long = 500,
    private val downloadTimeoutMillis: Long = 120_000,
) : AutoCloseable {
    private val root = documentsRoot.toAbsolutePath().normalize()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Job>(20)
    private val gate = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var closed = false
    private var retryAt = repo.documentIds().maxOfOrNull { repo.document(it)!!.record.retryAt } ?: 0
    init {
        ensureNoSymlinks(root)
        Files.createDirectories(root)
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        repo.documentIds().forEach { id ->
            val value = repo.document(id)!!
            if (value.record.state in setOf("QUEUED","DOWNLOADING","PROCESSING"))
                repo.saveDocument(value.copy(record=value.record.copy(state="ERROR",error="Обработка прервана. Повторите явно.")))
        }
        scope.launch { for (job in queue) { job.start(); job.join() } }
    }
    private fun get(id: String): StoredDocument {
        require(id.length in 1..36)
        return repo.document(id) ?: throw ResourceNotFound()
    }
    fun list(limit: Int = 50, offset: Int = 0, sourceKey: String? = null): DocumentList {
        require(limit in 1..100 && offset in 0..100_000)
        return repo.documents(limit,offset,sourceKey)
    }
    fun search(query: String, limit: Int = 50, offset: Int = 0): DocumentSearch {
        require(query.length in 1..256 && limit in 1..100 && offset in 0..100_000)
        return repo.searchDocuments(query,limit,offset)
    }
    private fun format(name: String): String? = name.substringAfterLast('.',"").lowercase().takeIf { it in setOf("pdf","docx","txt") }
    private fun file(raw: TdApi.Message): TdApi.Document? = (raw.content as? TdApi.MessageDocument)?.document
    private fun identity(file: TdApi.File): String = file.remote?.uniqueId?.takeIf { it.isNotBlank() }
        ?: file.remote?.id?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Telegram не подтвердил файл")
    private fun remember(message: ResearchMessage, document: TdApi.Document): DocumentRecord? {
        val format = format(document.fileName ?: "") ?: return null
        val file = document.document ?: return null
        val remote = identity(file)
        return synchronized(gate) {
            repo.documentReference(message.sourceKey!!,message.id,remote)?.record ?: run {
                require(repo.documentCount() < 10_000) { "Достигнут лимит 10000 документов" }
                val name = document.fileName.orEmpty().replace(Regex("[\\p{Cntrl}/\\\\]"),"_").take(255).ifBlank { "document.$format" }
                val record = DocumentRecord(UUID.randomUUID().toString(),message,name,document.mimeType.orEmpty().take(128),file.size,format,
                    state=if (file.size > MAX_FILE) "UNAVAILABLE" else "NOT_DOWNLOADED",error=if (file.size > MAX_FILE) "Файл превышает 25 МБ" else null)
                repo.saveDocument(StoredDocument(record,file.id,remote,null,"")); record
            }
        }
    }
    suspend fun register(sourceKey: String, messageId: Long): List<DocumentRecord> {
        val (message,raw) = reader.documentMessage(sourceKey,messageId)
        return file(raw)?.let { remember(message,it) }?.let(::listOf) ?: emptyList()
    }
    suspend fun discover(input: DocumentDiscoverInput): DocumentDiscovery {
        require(input.limit in 1..50)
        val page = reader.recent(input.sourceKey,input.before,input.limit)
        // Metadata discovery never downloads a byte. Exact file proof is obtained only for document candidates.
        val records = mutableListOf<DocumentRecord>()
        for (message in page.messages) {
            if (message.attachments?.none { it.kind == "document" && format(it.fileName.orEmpty()) != null } != false) continue
            records += register(input.sourceKey,message.id)
        }
        return DocumentDiscovery(records,page.nextCursor,page.notice)
    }
    fun download(id: String, reindex: Boolean = false): DocumentRecord = synchronized(gate) {
        check(!closed) { "Сервис остановлен" }
        val stored = get(id)
        if (jobs[id]?.isCompleted == false) return@synchronized stored.record
        require(stored.record.size in 0..MAX_FILE) { "Файл превышает 25 МБ" }
        require(maxOf(retryAt,stored.record.retryAt) <= System.currentTimeMillis()) { "Дождитесь окончания ограничения Telegram" }
        if (!reindex && stored.record.hasFile && stored.record.state in setOf("READY","EMPTY","NEEDS_OCR")) return@synchronized stored.record
        val queued = stored.copy(record=stored.record.copy(state="QUEUED",error=null,retryAt=0))
        val job = scope.launch(start=CoroutineStart.LAZY) { process(id,reindex) }
        repo.saveDocument(queued)
        jobs[id] = job
        if (queue.trySend(job).isFailure) {
            jobs.remove(id); job.cancel()
            repo.saveDocument(stored)
            throw IllegalArgumentException("Очередь заполнена (20 файлов). Повторите позже")
        }
        job.invokeOnCompletion { synchronized(gate) { if (jobs[id] === job) jobs.remove(id) } }
        queued.record
    }
    suspend fun cancel(id: String): DocumentRecord {
        var cancelled: Job? = null
        val value = synchronized(gate) {
            val current = get(id)
            val job = jobs.remove(id)
            if (job == null) return current.record
            cancelled = job
            job.cancel()
            current.copy(record=current.record.copy(state="ERROR",error="Отменено пользователем")).also(repo::saveDocument)
        }
        // The worker cancels its own TDLib transfer in finally; wait before allowing a new transfer.
        withContext(NonCancellable) { withTimeoutOrNull(3_000) { cancelled?.join() } }
        return value.record
    }
    private suspend fun save(value: StoredDocument) {
        currentCoroutineContext().ensureActive()
        val activeJob = currentCoroutineContext()[Job]
        synchronized(gate) {
            activeJob?.ensureActive()
            check(jobs.containsKey(value.record.id))
            repo.saveDocument(value)
        }
    }
    private suspend fun process(id: String, reindex: Boolean) {
        var value = get(id)
        var requested = false
        try {
            val deferred = synchronized(gate) { retryAt }
            if (deferred > System.currentTimeMillis()) {
                save(value.copy(record=value.record.copy(state="ERROR",error="Telegram ограничил запросы. Повторите позже.",retryAt=deferred)))
                return
            }
            var original = value.hash?.let(::blob)?.takeIf { safeRegular(it,root) }
            if (original == null || !value.record.hasFile) {
                val (_,raw) = reader.documentMessage(value.record.message.sourceKey!!,value.record.message.id)
                val current = file(raw)?.document ?: throw DocumentUnavailable()
                if (identity(current) != value.remoteId) throw DocumentUnavailable()
                require(current.size in 0..MAX_FILE) { "Файл превышает 25 МБ" }
                value=value.copy(fileId=current.id)
                val reusable=repo.reusableDocument(value.remoteId)
                if(reusable?.hash != null && reusable.record.hasFile && safeRegular(blob(reusable.hash),root)) {
                    original=blob(reusable.hash)
                    value=value.copy(hash=reusable.hash,record=value.record.copy(size=Files.size(original),hasFile=true,downloadedBytes=Files.size(original)))
                    save(value)
                }
                if(original == null) {
                require(current.size in 0..MAX_FILE) { "Файл превышает 25 МБ" }
                require(diskBytes() + 2 * (if(current.size == 0L) MAX_FILE else current.size) <= MAX_DISK) { "Локальный лимит 500 МБ исчерпан" }
                value = value.copy(record=value.record.copy(state="DOWNLOADING",downloadedBytes=0))
                save(value)
                requested = true
                lateinit var result: TdApi.File
                try { withTimeout(downloadTimeoutMillis) {
                    result = execute(TdApi.DownloadFile(current.id,1,0,if(current.size>0) current.size else MAX_FILE+1,false)) as TdApi.File
                    while (result.local?.isDownloadingCompleted != true) {
                        currentCoroutineContext().ensureActive()
                        require(result.id == current.id && result.size <= MAX_FILE && (result.local?.downloadedSize ?: 0) <= MAX_FILE)
                        if(result.local?.canBeDownloaded != true && result.local?.isDownloadingActive != true) throw DocumentUnavailable()
                        value = value.copy(record=value.record.copy(downloadedBytes=result.local?.downloadedSize ?: 0))
                        save(value)
                        delay(pollMillis)
                        result = execute(TdApi.GetFile(current.id)) as TdApi.File
                    }
                }
                } catch (_: TimeoutCancellationException) { throw IllegalStateException("Download timeout") }
                require(result.id == current.id && identity(result) == value.remoteId)
                val source = Path.of(result.local.path).toAbsolutePath().normalize()
                require(safeRegular(source,tdlibRoot)) { "Недопустимый путь файла Telegram" }
                val stored = storeOriginal(source,current.size)
                original = stored.first
                value = value.copy(hash=stored.second,record=value.record.copy(size=Files.size(original),hasFile=true,downloadedBytes=Files.size(original),state="DOWNLOADED"))
                save(value)
                }
            }
            currentCoroutineContext().ensureActive()
            value = value.copy(record=value.record.copy(state="PROCESSING",error=null))
            save(value)
            val result = runInterruptible { extractor.extract(original!!,value.record.format) }
            val allowance=(20_000_000-repo.documentTextChars(id)).coerceIn(0,200_000).toInt()
            val text=result.text.take(allowance)
            val exhausted=result.text.isNotBlank() && allowance==0
            value = value.copy(text=text,record=value.record.copy(state=if(exhausted) "ERROR" else result.status,
                hasText=text.isNotBlank(),truncated=result.truncated || text.length<result.text.length,error=when { exhausted -> "Лимит локального текстового индекса исчерпан"; result.status=="ERROR" -> "Не удалось извлечь текст"; else -> null }))
            save(value)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val rate = ex as? TelegramRequestException
            val retry = if (rate != null && (rate.code == 429 || rate.reason.startsWith("FLOOD_WAIT"))) {
                val seconds = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)",RegexOption.IGNORE_CASE).find(rate.reason)?.groupValues?.get(1)?.toLongOrNull() ?: 60
                System.currentTimeMillis() + seconds.coerceIn(1,604800)*1000
            } else 0
            val error = when {
                retry > 0 -> "Telegram ограничил запросы. Повторите после указанного времени."
                ex is DocumentUnavailable -> "Вложение изменилось или недоступно. Найдите публикацию заново."
                ex is TimeoutCancellationException -> "Время загрузки истекло"
                else -> "Не удалось обработать файл. Проверьте размер, доступ и свободное место."
            }
            if (retry > 0) synchronized(gate) { retryAt=maxOf(retryAt,retry) }
            save(value.copy(record=value.record.copy(state=if(ex is DocumentUnavailable) "UNAVAILABLE" else "ERROR",error=error,retryAt=retry)))
        } finally {
            if (requested) withContext(NonCancellable) { withTimeoutOrNull(2_000) {
                try { execute(TdApi.CancelDownloadFile(value.fileId,false)) } catch (_: Exception) { }
            } }
        }
    }
    private fun blob(hash: String): Path {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return root.resolve(hash)
    }
    /** Count both app originals and TDLib's document cache without following links or unbounded trees. */
    private fun diskBytes(): Long {
        var entries=0
        var bytes=0L
        for (directory in listOf(root,tdlibRoot.toAbsolutePath().normalize()).distinct()) {
            ensureNoSymlinks(directory)
            if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) continue
            Files.walk(directory,9).use { paths ->
                val iterator=paths.iterator()
                while(iterator.hasNext()) {
                    val path=iterator.next()
                    require(++entries<=20_000 && directory.relativize(path).nameCount<=8) { "Хранилище документов превышает предел обхода" }
                    val attrs=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                    require(attrs.isDirectory || attrs.isRegularFile) { "Недопустимый объект хранилища" }
                    if(attrs.isRegularFile) bytes=Math.addExact(bytes,attrs.size())
                    require(bytes<=MAX_DISK) { "Лимит хранилища документов исчерпан" }
                }
            }
        }
        return bytes
    }
    private fun storeOriginal(source: Path, expectedSize: Long): Pair<Path,String> {
        ensureNoSymlinks(root)
        val size = Files.size(source)
        require(size in 0..MAX_FILE && (expectedSize == 0L || size == expectedSize))
        require(diskBytes() + size <= MAX_DISK) { "Локальный лимит исчерпан" }
        val temp = Files.createTempFile(root,"pending-",".tmp")
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"))
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS).use { input -> Files.newOutputStream(temp).use { output ->
                val buffer = ByteArray(64*1024); var total = 0L
                while (true) { val count = input.read(buffer); if(count < 0) break
                    total += count; require(total <= MAX_FILE && total <= size)
                    digest.update(buffer,0,count); output.write(buffer,0,count)
                }
                require(total == size)
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = blob(hash)
            if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) require(safeRegular(target,root) && Files.size(target)==size)
            else Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)
            return target to hash
        } finally { Files.deleteIfExists(temp) }
    }
    fun record(id: String): DocumentRecord = get(id).record
    fun original(id: String): Pair<DocumentRecord,Path> {
        val value = get(id)
        val path = value.hash?.let(::blob) ?: throw ResourceNotFound()
        if (!value.record.hasFile || !safeRegular(path,root)) throw ResourceNotFound()
        return value.record to path
    }
    fun text(id: String): DocumentText {
        val value = get(id)
        if (!value.record.hasText) throw ResourceNotFound()
        return DocumentText(value.text,value.record.truncated)
    }
    override fun close() {
        synchronized(gate) { closed = true; jobs.values.toList().forEach { it.cancel() }; queue.close(); scope.cancel() }
        runBlocking { withTimeoutOrNull(3_000) { scope.coroutineContext[Job]?.join() } }
        synchronized(gate) {
            jobs.clear()
            repo.documentIds().forEach { id -> val value = get(id)
                if(value.record.state in setOf("QUEUED","DOWNLOADING","PROCESSING"))
                    repo.saveDocument(value.copy(record=value.record.copy(state="ERROR",error="Обработка прервана. Повторите явно.")))
            }
        }
    }
    private class DocumentUnavailable : Exception()
    companion object {
        const val MAX_FILE = 25L*1024*1024
        const val MAX_DISK = 500L*1024*1024
        internal fun ensureNoSymlinks(path: Path) {
            var current: Path? = path.toAbsolutePath().normalize()
            while(current != null) { require(!Files.isSymbolicLink(current)) { "Символические ссылки запрещены" }; current=current.parent }
        }
        internal fun safeRegular(path: Path, within: Path): Boolean = try {
            val file=path.toAbsolutePath().normalize(); val parent=within.toAbsolutePath().normalize()
            require(file.startsWith(parent) && file != parent)
            ensureNoSymlinks(file); ensureNoSymlinks(parent)
            Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && file.toRealPath().startsWith(parent.toRealPath())
        } catch (_: Exception) { false }
    }
}
