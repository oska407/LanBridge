# LanBridge 局域网跨端聊天

手机开热点即可变身「中转服务器」，同 Wi-Fi 下的 PC 浏览器免安装、零 CDN 直接收发文字 / 图片 / 文件。安卓 App 内置 Ktor 服务，PC 网页随 App 启动自动提供。

## 特性

- **零安装跨端**：手机开热点 → PC 浏览器访问 `http://<手机IP>:8080` 即用，无需任何客户端
- **内置 HTTP + WebSocket 服务**：Ktor CIO 引擎，HTTP 与 WS 同端口（默认 `:8080`）
- **图片选择器**：MediaStore 游标分页 + Glide 系统缩略图 + 滑动连续多选（仿微信划手）
- **微信中转**：`ACTION_SEND` 入站同步拷贝 `content://`；FileProvider 出站分享
- **双通道发送**：驻留拖拽/粘贴（≤9 个）+ 工具条批量直发
- **零持久化内存会话态**：进程退出即清，磁盘 0 残留
- **安全模型**：服务端仅绑热点网卡 IP + 校验来源子网（F-11 配对鉴权已取消）

## 技术栈

- Kotlin 100% + 传统 View 体系（非 Compose）
- Ktor 2.3.10（core / cio / websockets）
- Glide 4.16、kotlinx-coroutines 1.7.3、ZXing 3.5.3（本地二维码）
- AndroidX Material / Material3
- PC 网页：原生 vanilla JS，零外部依赖

## 环境要求

- `minSdk` 28 / `compileSdk` & `targetSdk` 34
- Gradle 8.5（wrapper 已内置）
- Android Studio + Android SDK

## 编译与运行

```bash
# 用 Android Studio 打开本目录（LanBridge 工程根）
# 首次同步会自动下载 Gradle 8.5
# 连接真机或模拟器后 Run
```

首次运行需授予：

- **相册权限**（读取图片/视频）
- **通知权限**（新消息提示）
- 若 `8080` 被占用：进入「设置」页改端口（范围 1024–65535，改端口自动重启服务）

## 使用流程

1. 手机开启**个人热点**
2. App 自动获取热点 IP（默认 `192.168.43.1:8080`）
3. 同热点 / 同 Wi-Fi 下的 PC 浏览器访问该地址
4. 两端即可互发文字、图片、文件

## 目录结构

```
app/src/main/
├── java/com/lanbridge/
│   ├── model/      消息与文件元数据、WS 协议
│   ├── server/     内存会话态 / 文件分块存储 / WsHub / 内嵌服务 / 传输引擎
│   ├── chat/       聊天页与多选、气泡适配器、长按菜单
│   ├── gallery/    相册游标分页、划选手势状态机
│   ├── settings/   设置仓库与设置页（端口校验、本地二维码）
│   ├── service/    前台服务 + 常驻通知 + 拷贝 PC 网页资源
│   ├── media/      图片压缩 / 保存到下载
│   ├── wechat/     微信入站拷贝 / 出站分享 / SEND 接收
│   ├── util/       热点 IP 获取 / 二维码生成
│   └── LanBridgeApp.kt
├── res/            colors/strings/dimens/themes + 7 个 layout + drawable + mipmap
└── assets/pc-web/  index.html / css/style.css / js/app.js
```

## 设计 Token

- 实心绿按钮：`#0A8A43`（对比度 4.4:1，达 WCAG AA）
- 辅助文字：`#6B6B6B`
- 启动图标底色：`#A3EDF6`（取自 logo）

## 已知限制

- 本仓库仅含源码，需在本机 Android Studio 中完成实际编译
- 传输串行排队（并发=1）；大文件走 Range 分块
- 会话态为内存态，App 进程退出即清空
