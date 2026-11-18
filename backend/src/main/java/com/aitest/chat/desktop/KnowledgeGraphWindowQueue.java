package com.aitest.chat.desktop;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 桌面壳三维知识图谱窗口队列：前端请求打开，Electron 轮询后弹出独立窗口。
 */
@Component
public class KnowledgeGraphWindowQueue {

	private final AtomicBoolean pendingOpen = new AtomicBoolean(false);

	private final boolean desktop;

	public KnowledgeGraphWindowQueue() {
		this.desktop = Boolean.getBoolean("app.desktop");
	}

	public boolean desktop() {
		return this.desktop;
	}

	/** 请求打开（或前置）知识图谱桌面窗口。 */
	public void requestOpen() {
		this.pendingOpen.set(true);
	}

	/** Electron 拉取：若有待打开则消费并返回 true。 */
	public boolean drainOpen() {
		return this.pendingOpen.compareAndSet(true, false);
	}

}
