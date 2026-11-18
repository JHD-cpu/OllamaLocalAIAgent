package com.aitest.chat.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class InstalledSoftwareScannerTest {

	@Test
	void dedupeKeepsExePath() {
		List<InstalledSoftwareItem> items = InstalledSoftwareScanner.dedupeForTest(List.of(
				new InstalledSoftwareItem("Google Chrome", "Google", "C:\\Chrome", ""),
				new InstalledSoftwareItem("Google Chrome", "Google", "C:\\Chrome", "C:\\Chrome\\chrome.exe")));
		assertEquals(1, items.size());
		assertEquals("C:\\Chrome\\chrome.exe", items.get(0).exePath());
	}

	@Test
	void resolveFindsKnownExe(@TempDir Path dir) throws Exception {
		Path chrome = dir.resolve("chrome.exe");
		Files.writeString(chrome, "x");
		List<InstalledSoftwareItem> items = List
			.of(new InstalledSoftwareItem("Google Chrome", "Google", dir.toString(), chrome.toString()));
		Map<String, String> resolved = InstalledSoftwareScanner.resolveForTest(items);
		assertEquals(chrome.toAbsolutePath().normalize().toString(), resolved.get("chrome"));
	}

	@Test
	void snapshotRoundTrip(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("installed-software.json");
		InstalledSoftwareSnapshot snapshot = new InstalledSoftwareSnapshot("2026-11-16T18:00:00",
				List.of(new InstalledSoftwareItem("Node.js", "OpenJS", "C:\\Node", "C:\\Node\\node.exe")),
				Map.of("node", "C:\\Node\\node.exe"));
		JsonMapper mapper = JsonMapper.builder().build();
		mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
		InstalledSoftwareSnapshot loaded = mapper.readValue(file.toFile(), InstalledSoftwareSnapshot.class);
		assertEquals(1, loaded.items().size());
		assertEquals("Node.js", loaded.items().get(0).name());
		assertEquals("C:\\Node\\node.exe", loaded.resolved().get("node"));
		assertTrue(Files.size(file) > 20);
	}

}
