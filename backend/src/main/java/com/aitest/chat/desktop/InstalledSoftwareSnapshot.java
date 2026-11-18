package com.aitest.chat.desktop;

import java.util.List;
import java.util.Map;

/** 一次软件扫描的快照：时间戳、条目与常用程序路径。 */
public record InstalledSoftwareSnapshot(String scannedAt, List<InstalledSoftwareItem> items, Map<String, String> resolved) {

   /** 构造无条目的占位快照。 */
   public static InstalledSoftwareSnapshot empty(String scannedAt) {
      return new InstalledSoftwareSnapshot(scannedAt, List.of(), Map.of());
   }
}
