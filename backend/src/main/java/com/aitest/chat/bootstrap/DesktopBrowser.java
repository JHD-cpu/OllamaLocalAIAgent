package com.aitest.chat.bootstrap;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** 非桌面打包模式下启动后自动打开本地前端页。 */
@Component
public class DesktopBrowser
implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(DesktopBrowser.class);
    private final String address;
    private final int port;

    /** 读取服务监听地址与端口。 */
    DesktopBrowser(@Value(value="${server.address:127.0.0.1}") String address, @Value(value="${server.port:8080}") int port) {
        this.address = address;
        this.port = port;
    }

    /** 应用就绪时按系统属性决定是否唤起浏览器。 */
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (Boolean.getBoolean("app.desktop") || !Boolean.getBoolean("app.open-browser")) {
            return;
        }
        String host = "0.0.0.0".equals(this.address) ? "127.0.0.1" : this.address;
        URI uri = URI.create("http://" + host + ":" + this.port + "/");
        try {
            if (!Desktop.isDesktopSupported()) {
                log.info("\u9875\u9762\u5730\u5740 {}", (Object)uri);
                return;
            }
            Desktop.getDesktop().browse(uri);
            log.info("\u5df2\u6253\u5f00\u6d4f\u89c8\u5668 {}", (Object)uri);
        }
        catch (IOException | RuntimeException ex) {
            log.warn("\u672a\u80fd\u6253\u5f00\u6d4f\u89c8\u5668 {}\uff1a{}", (Object)uri, (Object)ex.getMessage());
        }
    }
}