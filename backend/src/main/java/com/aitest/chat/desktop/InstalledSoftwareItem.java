package com.aitest.chat.desktop;

/** 注册表扫描到的一条已安装程序。 */
public record InstalledSoftwareItem(String name, String publisher, String installLocation, String exePath) {
}
