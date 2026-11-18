package com.aitest.chat.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 返回当前日志文件位置，便于界面提示与排查。 */
@RestController
@RequestMapping("/api/system")
public class SystemLogController {

	private final Path logFile;

	public SystemLogController(@Value("${logging.file.name:logs/app.log}") String logFile) {
		this.logFile = Path.of(logFile).toAbsolutePath().normalize();
	}

	@GetMapping("/log")
	public Map<String, Object> logInfo() {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("path", this.logFile.toString());
		info.put("errorPath", this.logFile.toString() + ".error");
		info.put("exists", Files.isRegularFile(this.logFile));
		try {
			info.put("size", Files.isRegularFile(this.logFile) ? Files.size(this.logFile) : 0L);
		}
		catch (Exception ex) {
			info.put("size", 0L);
		}
		return info;
	}

}
