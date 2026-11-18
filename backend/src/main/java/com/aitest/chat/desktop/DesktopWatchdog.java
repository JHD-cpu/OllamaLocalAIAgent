package com.aitest.chat.desktop;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 桌面壳进程心跳监管：超时未心跳则退出 JVM。 */
@Component
public class DesktopWatchdog {
   private static final Logger log = LoggerFactory.getLogger(DesktopWatchdog.class);
   private static final long STARTUP_GRACE_MS = 60000L;
   private static final long SILENCE_LIMIT_MS = 10000L;
   private final ApplicationContext context;
   private final boolean enabled;
   private final long startedAt = System.currentTimeMillis();
   private final AtomicLong lastHeartbeat = new AtomicLong(0L);
   private volatile boolean exiting;

   /** 仅在 {@code app.desktop} 为 true 时启用监管。 */
   public DesktopWatchdog(ApplicationContext context) {
      this.context = context;
      this.enabled = Boolean.getBoolean("app.desktop");
      if (this.enabled) {
         log.info("桌面监管已开启，超过 {} 秒没有心跳会退出", 10L);
      }
   }

   /** 是否处于桌面托管模式。 */
   public boolean enabled() {
      return this.enabled;
   }

   /** 由前端定期调用以刷新最后心跳时间。 */
   public void heartbeat() {
      if (this.enabled) {
         this.lastHeartbeat.set(System.currentTimeMillis());
      }
   }

   /** 供诊断接口读取最近一次心跳毫秒时间戳。 */
   public long lastHeartbeatAt() {
      return this.lastHeartbeat.get();
   }

   /** 每秒检查是否超过静默时限或启动宽限期无心跳。 */
   @Scheduled(
      fixedDelay = 1000L
   )
   void check() {
      if (this.enabled && !this.exiting) {
         long now = System.currentTimeMillis();
         long last = this.lastHeartbeat.get();
         if (last == 0L) {
            if (now - this.startedAt > 60000L) {
               this.exit("启动后一直没有收到桌面心跳");
            }
         } else {
            if (now - last > 10000L) {
               this.exit("桌面心跳中断超过 10 秒");
            }
         }
      }
   }

   /** 记录原因并优雅关闭 Spring 后退出进程。 */
   private void exit(String reason) {
      this.exiting = true;
      log.warn("服务退出：{}", reason);
      if (this.context instanceof ConfigurableApplicationContext configurable) {
         int code = SpringApplication.exit(configurable, new ExitCodeGenerator[]{() -> 0});
         System.exit(code);
      } else {
         System.exit(0);
      }
   }
}
