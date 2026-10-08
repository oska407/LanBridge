# LanBridge 推送到 GitHub 教程

本教程带你把 `LanBridge` 安卓源码仓库推送到 GitHub。无需任何编程基础，跟着步骤走即可。

---

## 一、准备工作（只需做一次）

### 1. 注册 GitHub 账号
打开 https://github.com 注册（已有账号可跳过）。

### 2. 安装 Git
- 下载：https://git-scm.com/downloads
- 安装时一路默认「Next」即可。
- 安装后验证：打开**命令提示符（CMD）**或 **PowerShell**，输入：
  ```bash
  git --version
  ```
  能看到版本号（如 `git version 2.xx.x`）就说明装好了。

### 3. 在 GitHub 上新建一个空仓库
1. 登录 GitHub → 右上角「+」→ **New repository**。
2. Repository name 填 `LanBridge`（或你喜欢的名字）。
3. **不要**勾选「Add a README file」「.gitignore」「License」（我们本地已有）。
4. 点 **Create repository**。
5. 创建后会看到一个地址，形如：
   ```
   https://github.com/你的用户名/LanBridge.git
   ```
   复制它，下面要用。

---

## 二、命令行方式（推荐，最通用）

> 以下命令在 `LanBridge` 文件夹内执行。先进入该目录：
> ```bash
> cd "G:\Workbuddy\8. 局域网跨端聊天软件\LanBridge"
> ```

### 第 1 步：初始化并配置身份
```bash
git init
git config user.name "你的名字"
git config user.email "你的邮箱@example.com"
```

### 第 2 步：把文件加入暂存区
```bash
git add .
```
> 本工程已自带 `.gitignore`，会自动**排除** `build/`、`*.iml`、`.gradle/` 等构建产物，只提交干净源码。

提交前可先检查会不会误传多余文件：
```bash
git status
```
正常情况下应只看到 `.kt`、`.xml`、`.gradle`、`.png`、`README.md`、`PUSH_GUIDE.md` 等源码文件。

### 第 3 步：提交
```bash
git commit -m "feat: LanBridge 局域网跨端聊天 Android App"
```

### 第 4 步：关联远程仓库
把下面地址换成**你自己的**仓库地址：
```bash
git remote add origin https://github.com/你的用户名/LanBridge.git
```

### 第 5 步：推送
```bash
git push -u origin main
```
> 如果提示分支名冲突（如远程默认是 `master`），用：
> ```bash
> git branch -M main
> git push -u origin main
> ```

### 第 6 步：登录验证
推送时会弹出 GitHub 登录框，或使用**个人访问令牌（Token）**作为密码：
- 令牌获取：GitHub → Settings → Developer settings → Personal access tokens → 勾选 `repo` 权限生成。
- 之后 push 可能仍需再次输入令牌（未缓存时）。

推送成功后，刷新 GitHub 页面即可看到全部源码。

---

## 三、GitHub Desktop 方式（纯图形化，适合不想敲命令）

1. 下载安装：https://desktop.github.com/
2. 登录你的 GitHub 账号。
3. **File → Add local repository** → 选择 `LanBridge` 文件夹 → 按提示「Create a repository」或「Add」。
4. 左侧确认改动列表（应全是源码文件），在左下角 Summary 填 `feat: LanBridge 局域网跨端聊天 Android App`，点 **Commit to main**。
5. 点顶部 **Publish repository**（首次）或 **Push origin**（之后）→ 选好仓库名 → 确认发布。

---

## 四、本项目推送注意事项

- ✅ **不要传构建产物**：`build/`、`*.iml`、`.gradle/` 已被 `.gitignore` 排除，不会上传，仓库保持干净。
- ✅ **大文件**：源码里图片仅 15 张 mipmap 图标（几 KB~几十 KB），无大体积资源，普通 GitHub 仓库即可，无需 Git LFS。
- ✅ **隐私**：`local.properties`（含 SDK 路径）已被忽略；App 不收集任何用户数据。
- ⚠️ **敏感信息**：本工程无任何密钥/密码硬编码，可安全公开。

---

## 五、之后怎么更新（改完代码再推）

```bash
cd "G:\Workbuddy\8. 局域网跨端聊天软件\LanBridge"
git add .
git commit -m "描述这次改了什么"
git push
```
> 第一次 push 用了 `-u origin main` 后，后续直接 `git push` 即可。

---

## 六、常见问题

**Q：push 时提示 `Authentication failed`？**
A：GitHub 已不支持账户密码登录，需用 **Personal access token** 代替密码（见上文第 6 步）。建议勾选「记住凭据」避免每次输入。

**Q：提示 `remote already exists`？**
A：说明已关联过远程，先移除再添加：
```bash
git remote remove origin
git remote add origin https://github.com/你的用户名/LanBridge.git
```

**Q：误传了大文件 / 想重置？**
A：在本地删除文件后 `git rm` 并提交即可；若已 push，可用 `git revert` 或强制回退（谨慎）。

**Q：不想公开，想建私有仓库？**
A：第 3 步新建仓库时选 **Private** 即可，其余步骤完全相同。
