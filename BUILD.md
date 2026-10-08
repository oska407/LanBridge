# LanBridge 编译指南（生成 APK）

本工程是 Android 项目（Kotlin + Ktor 内嵌服务），需要 **Android SDK** 才能编译。下面给出两种编译方式。

> 当前工程已包含 `gradle/wrapper/gradle-wrapper.properties`（指向 Gradle 8.5），但**尚未包含 `gradlew` 脚本与 `gradle-wrapper.jar`**。这两种文件会在你**首次用 Android Studio 打开本工程时自动生成**，因此推荐用方式一（Android Studio），无需手动处理。

---

## 方式一：Android Studio（推荐，最省心）

### 1. 安装
- 下载 Android Studio：https://developer.android.com/studio
- 安装时勾选 **Android SDK**、**Android SDK Platform**（选 API 34）、**Android SDK Build-Tools**。

### 2. 打开工程
- `File → Open`，选择本仓库根目录 `LanBridge`（内含 `settings.gradle.kts` 的那个文件夹）。
- 首次打开会自动：
  1. 下载 Gradle 8.5（wrapper 已配置，自动下载）；
  2. 解析依赖（Ktor / Glide / ZXing / 等，需联网）；
  3. **自动生成 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar`**（之后命令行也能用了）。

### 3. 编译 / 运行
- 连真机（开启「开发者选项 → USB 调试」）或新建模拟器（API 28 及以上）。
- 点工具栏 ▶ **Run**，或 `Build → Make Project` 先验证能否编过。
- 出 APK：`Build → Build Bundle(s) / APK(s) → Build APK(s)`。
- 产物位置：`app/build/outputs/apk/debug/`（调试包）或 `app/build/outputs/apk/release/`（发布包）。

---

## 方式二：命令行 Gradle

前提：已安装 Android SDK，并配置 `ANDROID_HOME` 环境变量，或在工程根目录 `local.properties` 写 `sdk.dir=你的SDK路径`。

```bash
cd LanBridge
# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew
./gradlew assembleDebug      # 调试包，可直接侧载测试
# 或发布包（需先配置签名，见下文）
./gradlew assembleRelease
```

> 若提示找不到 `gradle` / `gradlew`：说明 wrapper 还没生成。两种办法：
> - 先按「方式一」用 Android Studio 打开一次工程（会自动生成）；
> - 或本机已装 Gradle CLI，执行 `gradle wrapper --gradle-version 8.5` 生成后重试。

---

## 发布签名（给别人安装 / 上架才需要）

- **调试包**自带系统调试签名，可直接装到自己的设备测试，**无需**额外配置。
- **发布包（Release）**需配置签名。当前 `app/build.gradle.kts` 的 `release` 未带 `signingConfig`，发布前请补充：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PWD")
            keyAlias = "lanbridge"
            keyPassword = System.getenv("KEY_PWD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

生成签名密钥库（一次性）：
```bash
keytool -genkeypair -v -keystore release.keystore -alias lanbridge -keyalg RSA -keysize 2048 -validity 10000
```
> ⚠️ 密钥库和密码务必离线妥善保管，丢失后无法更新已发布的应用。建议用环境变量传入密码，不要写进代码/提交到仓库。

---

## 编译环境要求

| 项 | 版本 |
|----|------|
| minSdk | 28 |
| compileSdk / targetSdk | 34 |
| Gradle | 8.5（wrapper 自动下载） |
| Android Gradle Plugin | 8.5.2（已声明） |
| Kotlin | 1.9.24（已声明） |
| JDK | 17（AGP 8 要求） |

---

## 常见问题

**Q：依赖下载慢 / 失败？**
A：检查网络；如需国内镜像，可在 `settings.gradle.kts` 的 `repositories` 里加上阿里云 Maven 源。

**Q：首次打开报「SDK location not found」？**
A：在 `local.properties` 写 `sdk.dir=你的SDK绝对路径`，或在 Android Studio 的 `File → Project Structure` 里指定 SDK。

**Q：编译报插件版本找不到？**
A：已在 `settings.gradle.kts` 写明 AGP / Kotlin 版本（8.5.2 / 1.9.24），正常同步即可；若仍报错，确认 Gradle 用的是 8.5。

**Q：PC 网页要不要单独构建？**
A：不需要。`assets/pc-web/`（index.html / css / js）是原生静态文件，由前台服务在运行时拷贝到缓存并直接托管，无额外打包步骤。

**Q：本机没有 Android SDK，能不能在这里直接编？**
A：不能。编译 Android 应用必须装 Android SDK + Gradle，请在你自己的电脑上按上述方式编译。
