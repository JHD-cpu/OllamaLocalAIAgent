package com.aitest.chat.tools.code;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeToolsTest {

	@Test
	void allowlistsSafeDiagnostics() {
		assertTrue(CodeTools.allowlisted("dir"));
		assertTrue(CodeTools.allowlisted("python -V"));
		assertTrue(CodeTools.allowlisted("git status"));
		assertFalse(CodeTools.allowlisted("curl http://evil"));
		assertFalse(CodeTools.allowlisted("format C:"));
	}

	@Test
	void deniesDangerousPatterns() {
		assertNotNull(CodeTools.denyReason("format C:"));
		assertNotNull(CodeTools.denyReason("del /s /q C:\\Windows"));
		assertNotNull(CodeTools.denyReason("dir | findstr x"));
		assertNull(CodeTools.denyReason("python -V"));
		assertNull(CodeTools.denyReason("dir"));
	}

}
