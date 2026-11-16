package com.aitest.chat.rag;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * 上传文档后先记下「处理中」，后台再解析、切块、嵌入。
 * 删除和写入完成共用一把锁，避免文档正在入库时被删掉后向量又写回来。
 */
@Service
public class KnowledgeService implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

	private final RagProperties properties;

	private final KnowledgeRepository repository;

	private final TextStore textStore;

	private final DocumentTextReader reader;

	private final KnowledgeVectorStore vectorStore;

	private final EmbeddingSemanticChunker chunker;

	private final Object gate = new Object();

	private final ExecutorService indexer = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "knowledge-index");
		thread.setDaemon(true);
		return thread;
	});

	public KnowledgeService(RagProperties properties, KnowledgeRepository repository, TextStore textStore,
			DocumentTextReader reader, KnowledgeVectorStore vectorStore, EmbeddingSemanticChunker chunker) {
		this.properties = properties;
		this.repository = repository;
		this.textStore = textStore;
		this.reader = reader;
		this.vectorStore = vectorStore;
		this.chunker = chunker;
	}

	public KnowledgeDocumentView upload(String filename, byte[] bytes) {
		String name = filename == null ? "" : Path.of(filename).getFileName().toString().trim();
		String extension = DocumentTextReader.extension(name);
		if (!"pdf".equals(extension) && !"txt".equals(extension) && !"md".equals(extension)) {
			throw new IllegalArgumentException("只支持 pdf、txt、md");
		}
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("文件是空的");
		}
		if (bytes.length > this.properties.getMaxUploadBytes()) {
			throw new IllegalArgumentException("文件不能超过 20MB");
		}
		String id = UUID.randomUUID().toString();
		synchronized (this.gate) {
			Path original = this.textStore.writeOriginal(id, name, bytes);
			KnowledgeRepository.StoredDocument stored = new KnowledgeRepository.StoredDocument(id, name, extension,
					"PROCESSING", null, null, null, original.toString(), null, LocalDateTime.now());
			this.repository.insertDocument(stored);
		}
		this.indexer.execute(() -> index(id));
		return view(this.repository.find(id).orElseThrow());
	}

	public List<KnowledgeDocumentView> list() {
		return this.repository.list().stream().map(KnowledgeService::view).toList();
	}

	/** 先删向量，再删文本和数据库记录。失败文档如果向量库没连上，仍删掉本地记录。 */
	public void delete(String id) {
		synchronized (this.gate) {
			KnowledgeRepository.StoredDocument document = this.repository.find(id)
				.orElseThrow(() -> new IllegalArgumentException("找不到这个文档"));
			if ("FAILED".equals(document.status())) {
				try {
					this.vectorStore.deleteDocument(id);
				}
				catch (RuntimeException ex) {
					log.warn("删除失败文档时向量库不可用，仍移除本地记录 id={}", id);
				}
			}
			else {
				this.vectorStore.deleteDocument(id);
			}
			this.textStore.deleteAll(id);
			this.repository.delete(document.id());
		}
	}

	/** 用问题检索知识库。没有开启或没有命中时返回空列表，由调用方决定要不要改提示词。 */
	public List<KnowledgeHit> retrieve(String question, int topK) {
		if (question == null || question.isBlank()) {
			return List.of();
		}
		List<KnowledgeHit> hits = new ArrayList<>();
		for (Document document : this.vectorStore.search(question, topK)) {
			String text = document.getText() == null ? "" : document.getText().trim();
			if (text.isEmpty()) {
				continue;
			}
			Object filename = document.getMetadata().get("filename");
			hits.add(new KnowledgeHit(filename == null ? "资料" : filename.toString(), text));
		}
		return hits;
	}

	private void index(String id) {
		boolean sentToVectorStore = false;
		try {
			KnowledgeRepository.StoredDocument document = this.repository.find(id).orElse(null);
			if (document == null) {
				return;
			}
			String text = this.reader.read(document.filename(), Path.of(document.originalPath()));
			if (text.isBlank()) {
				throw new IllegalStateException("没有提取到文字");
			}
			List<String> chunks = this.chunker.split(text);
			synchronized (this.gate) {
				// 解析期间如果用户删了文档，就不要再把向量写回去。
				if (this.repository.find(id).isEmpty()) {
					return;
				}
				Path textPath = this.textStore.writeText(id, text);
				List<Document> vectors = new ArrayList<>();
				for (int i = 0; i < chunks.size(); i++) {
					String chunkId = UUID.randomUUID().toString();
					Path chunkPath = this.textStore.writeChunk(id, i, chunks.get(i));
					this.repository.insertChunk(chunkId, id, i, chunkPath.toString(), chunks.get(i).length());
					vectors.add(new Document(chunkId, chunks.get(i),
							Map.of("documentId", id, "chunkId", chunkId, "filename", document.filename())));
				}
				sentToVectorStore = true;
				this.vectorStore.add(vectors);
				this.repository.markReady(id, text.length(), chunks.size(), textPath.toString());
			}
			log.info("知识库入库完成 id={} chunks={}", id, chunks.size());
		}
		catch (Exception ex) {
			log.warn("知识库入库失败 id={}: {}", id, ex.getMessage());
			synchronized (this.gate) {
				if (this.repository.find(id).isPresent()) {
					if (sentToVectorStore) {
						try {
							this.vectorStore.deleteDocument(id);
						}
						catch (RuntimeException ignored) {
							log.warn("清理失败文档的向量没有成功 id={}", id);
						}
					}
					this.repository.markFailed(id, brief(ex));
				}
			}
		}
	}

	private static String brief(Throwable ex) {
		String message = ex.getMessage() == null ? "处理失败" : ex.getMessage().replace('\n', ' ').trim();
		return message.length() > 1000 ? message.substring(0, 1000) : message;
	}

	private static KnowledgeDocumentView view(KnowledgeRepository.StoredDocument document) {
		return new KnowledgeDocumentView(document.id(), document.filename(), document.mediaType(), document.status(),
				document.errorMessage(), document.charCount(), document.chunkCount(), document.createdAt());
	}

	@Override
	public void destroy() {
		this.indexer.shutdown();
	}

}
