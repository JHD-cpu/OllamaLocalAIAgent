package com.aitest.chat.api;

import com.aitest.chat.desktop.DesktopWatchdog;
import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.desktop.InstalledSoftwareSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 桌面客户端心跳与本机软件快照 API。 */
@RestController
@RequestMapping(value={"/api/desktop"})
public class DesktopController {
    private final DesktopWatchdog watchdog;
    private final InstalledSoftwareCatalog softwareCatalog;

    /** 注入桌面看门狗与已安装软件目录。 */
    public DesktopController(DesktopWatchdog watchdog, InstalledSoftwareCatalog softwareCatalog) {
        this.watchdog = watchdog;
        this.softwareCatalog = softwareCatalog;
    }

    /** 返回桌面模式是否启用及最近心跳时间。 */
    @GetMapping(value={"/health"})
    public Map<String, Object> health() {
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("desktop", this.watchdog.enabled());
        body.put("lastHeartbeat", this.watchdog.lastHeartbeatAt());
        return body;
    }

    /** 记录桌面客户端心跳并返回时间戳。 */
    @PostMapping(value={"/heartbeat"})
    public Map<String, Object> heartbeat() {
        this.watchdog.heartbeat();
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        body.put("at", this.watchdog.lastHeartbeatAt());
        return body;
    }

    /** 返回本机已安装软件快照。 */
    @GetMapping(value={"/software"})
    public InstalledSoftwareSnapshot software() {
        return this.softwareCatalog.snapshot();
    }
}