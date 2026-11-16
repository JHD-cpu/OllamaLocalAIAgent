package com.aitest.chat.rag;

import java.time.Duration;
import java.util.List;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 知识库接口：上传、列表、删除。入库在后台进行，列表里的状态会从处理中变成就绪或失败。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

	private final KnowledgeService knowledgeService;

	public KnowledgeController(KnowledgeService knowledgeService) {
		this.knowledgeService = knowledgeService;
	}

	@GetMapping("/documents")
	public List<KnowledgeDocumentView> documents() {
		return this.knowledgeService.list();
	}

	@PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<KnowledgeDocumentView> upload(@RequestPart("file") FilePart file) {
		return DataBufferUtils.join(file.content())
			.timeout(Duration.ofMinutes(2))
			.switchIfEmpty(Mono.error(new IllegalArgumentException("请选择文件")))
			.publishOn(Schedulers.boundedElastic())
			.map(buffer -> {
				try {
					return this.knowledgeService.upload(file.filename(), toBytes(buffer));
				}
				finally {
					DataBufferUtils.release(buffer);
				}
			});
	}

	@DeleteMapping("/documents/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		this.knowledgeService.delete(id);
	}

	private static byte[] toBytes(DataBuffer buffer) {
		byte[] bytes = new byte[buffer.readableByteCount()];
		buffer.read(bytes);
		return bytes;
	}

}
