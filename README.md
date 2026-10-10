# 研迹部署与运行指南

本指南帮助使用者从 GitHub 获取研迹，在自己的 Windows 电脑上部署数据库、文件存储、业务后端和 AI Agent，再把 Android 客户端安装到手机上。电脑运行服务，手机通过同一局域网访问电脑的业务接口。按下面步骤可以完成一次完整安装。

## 评审材料

比赛提交材料集中放在 [docs/提交材料](docs/提交材料) 目录，供评委查看：

| 材料 | 文件 |
| --- | --- |
| 项目演示视频 | [演示视频.mp4](docs/提交材料/演示视频.mp4)（约 18 MB，可下载播放） |
| 技术文档 | [研迹-技术文档.docx](docs/提交材料/研迹-技术文档.docx) |
| 评测用例集 | [研迹-评测用例集.docx](docs/提交材料/研迹-评测用例集.docx) |
| 部署说明提交文件 | [README.md](docs/提交材料/README.md) |
| 合规说明 | [合规说明.pdf](docs/提交材料/合规说明.pdf) |

需要部署运行项目时，继续阅读下方的安装步骤。

## 1. 安装前准备

准备一台 Windows 10/11 64 位电脑、一部 Android 手机和可访问互联网的网络。电脑与手机连接同一 Wi-Fi；AI 功能还需要可用的阿里云百炼 API Key 和模型额度。

| 软件 | 用途与安装要求 |
| --- | --- |
| [Git](https://git-scm.com/download/win) | 获取项目源码，安装时启用命令行使用 |
| [JDK 21](https://adoptium.net/temurin/releases/?version=21) 或更高版本 | 编译和运行两个 Java 服务；设置 `JAVA_HOME` 并将 `bin` 加入 PATH |
| [Maven 3.9](https://maven.apache.org/download.cgi) 或更高版本 | 解压安装，将 `bin` 加入 PATH |
| [MySQL 8.4 LTS](https://dev.mysql.com/doc/refman/8.4/en/windows-installation.html) | 安装 Server 和命令行客户端，设置并记住 root 密码，启动 MySQL Windows 服务，默认端口 3306 |
| [Go 1.24](https://go.dev/dl/) 或更高版本 | 从源码安装 MinIO 文件存储服务 |
| [Flutter stable](https://docs.flutter.dev/install/manual) | 将 Flutter 的 `bin` 加入 PATH，编译 Android APK |
| [Android Studio](https://developer.android.com/studio) | 安装 Android SDK、Build-Tools、Command-line Tools 和 Platform-Tools，按 [Flutter Android 配置说明](https://docs.flutter.dev/platform-integration/android/setup) 完成环境配置 |

安装完成后重新打开 PowerShell，执行：

```powershell
git --version
java -version
mvn -version
mysql --version
go version
flutter doctor
flutter doctor --android-licenses
```

`flutter doctor` 的 Android toolchain 项应通过。MySQL 命令不在 PATH 时，可以在后续配置中填写 `mysql.exe` 的完整路径。JDK 必须包含编译器，不能仅安装 JRE。

安装本指南使用的 MinIO 版本：

```powershell
go install github.com/minio/minio@RELEASE.2025-09-07T16-13-09Z
& "$env:USERPROFILE\go\bin\minio.exe" --version
```

如果设置过 `GOBIN`，使用该目录中的 `minio.exe`。MinIO 安装方式和许可见 [上游项目](https://github.com/minio/minio)。首次下载 Maven、Go、Flutter 依赖需要联网。

## 2. 从 GitHub 获取项目

项目仓库已经包含业务后端、AI Agent 和 Android 客户端的全部源码。在任意源码存放目录打开 PowerShell，执行：

```powershell
git clone https://github.com/abcElla/yanji.git yanji
cd yanji
```

仓库目录结构如下，部署脚本自动定位各工程，不要求固定盘符或安装目录：

```text
yanji/
├─ README.md                     完整部署指南
├─ backend-java/
│  ├─ deploy/                     配置、部署及数据库脚本
│  └─ backend-java/               业务后端源码和 sql/
├─ agent-java/
│  └─ agent-java/                 AI Agent 源码
└─ frontend-flutter/
   └─ frontend/apps/mobile/      Android 客户端源码
```

以下电脑端命令均在 `backend-java` 目录执行。三个服务启动命令分别在不同的 PowerShell 窗口运行。每个窗口都进入 `yanji/backend-java` 目录。

```powershell
cd backend-java
Set-ExecutionPolicy -Scope Process Bypass
```

此策略只对当前窗口生效；每个启动窗口都可以执行一次。也可用 `powershell -ExecutionPolicy Bypass -File .\deploy\脚本名.ps1` 调用。

## 3. 填写运行配置

```powershell
.\deploy\setup.ps1
```

按提示填写 JDK 安装目录、MySQL 客户端路径、MinIO 程序路径，设置业务数据库账号密码，并输入百炼 API Key。密码与 API Key 输入时不显示。脚本自动生成文件存储密码和两个服务通信密钥，保存到 `deploy/deployment.local.json`。

如果暂时没有 API Key，可以先留空，完成数据库和普通业务部署；配置有效 Key 后再启动 Agent。Key 可在 [阿里云百炼控制台](https://bailian.console.aliyun.com/) 获取，模型默认为 `qwen-plus`。

配置文件可在本机编辑；端口被占用时也在这里修改：

| 配置项 | 默认值或填写内容 |
| --- | --- |
| `JavaHome` | JDK 21+ 安装目录；留空时使用 PATH 中的 Java |
| `MysqlExe` / `MinioExe` | 对应程序路径，可包含空格 |
| `MysqlHost` / `MysqlPort` | `127.0.0.1` / `3306` |
| `Database` / `DatabaseUser` | `yanji` / `yanji_app`；名称使用字母、数字和下划线 |
| `DatabasePassword` | 使用者设置的数据库应用账号密码 |
| `BackendPort` / `AgentPort` | `8000` / `8091` |
| `MinioPort` / `MinioConsolePort` | `9000` / `9001` |
| `ModelApiKey` / `ModelName` | 使用者的百炼 Key / `qwen-plus` |
| `MinioPassword` / `InternalServiceKey` / `AgentReceiverKey` | 自动生成，无需手工复制到其他服务 |

两个 Java 服务从同一份配置读取通信密钥和地址，避免配置不一致。`deployment.local.json` 已被 Git 忽略，里面的密码和密钥只保存在使用者自己的电脑上。

## 4. 初始化数据库并导入数据

先确认 MySQL 服务已经运行，执行：

```powershell
.\deploy\init-database.ps1 -WithDemo
```

输入 MySQL root 密码后，脚本创建 `yanji` 数据库和 `yanji_app` 应用账号，授予该账号对业务数据库的权限，导入当前完整表结构，并执行增量迁移与演示数据导入。应用运行不使用 root 账号。已存在的应用账号密码不会被脚本重置；应让本地配置与该账号密码保持一致。

仓库已经提供：

| 文件或脚本 | 用途 |
| --- | --- |
| `backend-java/sql/000_current_schema_mysql.sql` | 空数据库的完整业务表结构 |
| `backend-java/sql/007_theses.sql` 至 `010_allow_multiple_weekly_reports.sql` | 当前部署所需的论文、科研跟进、经验记录及周报增量迁移 |
| `deploy/migrate-database.ps1` | 按顺序运行上述迁移，在数据库记录文件名和 SHA-256；已完成的迁移自动跳过 |
| `deploy/data/demo.sql` | 可公开导入的演示账号、课题组、导师关系、阶段和任务数据 |
| `deploy/import-data.ps1` | 导入仓库演示数据或使用者指定的 SQL 数据文件 |

`-WithDemo` 可省略，省略后只有业务表结构，使用者可在手机中注册账号并建立自己的课题组。导入演示数据后可直接登录：

| 身份 | 用户名 | 初始密码 |
| --- | --- | --- |
| 导师 | `demo_mentor` | `YanjiDemo2026!` |
| 学生 | `demo_student` | `YanjiDemo2026!` |

这些数据用于安装后的功能体验，不包含真实用户和未公开科研资料。正常使用前修改演示账号密码。演示数据可重复导入，不覆盖已存在的演示记录；用户名冲突时脚本停止。

已经部署过当前表结构时，更新代码后的数据库迁移命令为：

```powershell
.\deploy\migrate-database.ps1
```

迁移脚本保留业务数据；执行前仍应备份数据库。不要修改已执行过的迁移 SQL。更早的数据库若尚未包含当前基础表，需要先补齐对应版本的历史迁移；本指南的首次安装直接使用完整表结构，无需逐个运行 `001` 至 `006`。

导入自己的、与当前表结构兼容的数据文件：

```powershell
.\deploy\import-data.ps1 -SqlFile "C:\data\yanji-data.sql"
```

SQL 文件会按自身内容执行，导入前确认目标数据库和文件内容。包含附件的数据还需要同时恢复对应的 MinIO 对象；仅导入数据库不能恢复附件。仓库演示数据不依赖附件文件。

## 5. 编译并启动电脑端服务

先编译业务后端和 Agent：

```powershell
.\deploy\build.ps1
```

看到两个 Maven 项目均显示 `BUILD SUCCESS` 后，在第一个 PowerShell 窗口启动文件存储：

```powershell
.\deploy\start-minio.ps1
```

保持该窗口运行。在第二个窗口进入同一个 `backend-java` 目录，创建附件存储桶，然后启动业务后端：

```powershell
.\deploy\init-storage.ps1
.\deploy\start-backend.ps1
```

`init-storage.ps1` 使用后端已有的 MinIO 依赖创建私有存储桶 `yanji-files`，已存在时直接复用。在第三个窗口启动 Agent：

```powershell
.\deploy\start-agent.ps1
```

电脑端部署完成后默认服务地址如下：

| 服务 | 地址 | 使用方式 |
| --- | --- | --- |
| 业务后端 | `http://电脑局域网IP:8000` | 手机访问的统一 API，后端监听电脑网络接口 |
| AI Agent | `http://127.0.0.1:8091` | 后端内部调用，配置自动包含 `/internal/v1/tasks` 路径 |
| MinIO API | `http://127.0.0.1:9000` | 后端读写附件 |
| MinIO 控制台 | `http://127.0.0.1:9001` | 本机管理附件，使用本地配置的 MinIO 账号和密码登录 |

默认采用 MySQL 持久化、MinIO 附件存储和关键词检索；基础部署无需 Redis 或向量服务。混合检索需另外部署嵌入服务并配置相应参数。电脑服务运行期间保持开机、联网，避免进入休眠。停止服务时在对应窗口按 `Ctrl+C`；下次使用只需启动 MySQL 和以上三个服务，不必重复初始化数据库。

## 6. 在 Android 手机上安装和连接

先让电脑与手机连接同一个 Wi-Fi。在电脑执行 `ipconfig`，找到当前 Wi-Fi 或以太网适配器的 IPv4 地址，例如 `192.168.1.100`。手机配置必须使用这个地址，不能使用 `127.0.0.1`、`localhost` 或 Android 模拟器地址 `10.0.2.2`。

在管理员 PowerShell 中开放业务 API 的端口，仅限专用网络和本地子网：

```powershell
New-NetFirewallRule -DisplayName "Yanji API 8000" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -Profile Private -RemoteAddress LocalSubnet
```

Windows 网络类型设为可信的专用网络；若修改过 `BackendPort`，同步修改防火墙端口和手机 API 地址。无需对手机开放 MySQL、Agent 或 MinIO 端口。

先在手机浏览器访问 `http://192.168.1.100:8000/health/live`，看到成功响应后再构建 APK。下面 IP 只是示例，执行时替换成自己电脑的实际 IPv4：

```powershell
.\deploy\build-mobile.ps1 -ApiBaseUrl "http://192.168.1.100:8000"
```

生成文件位于 `frontend-flutter/frontend/apps/mobile/build/app/outputs/flutter-apk/app-debug.apk`。将 APK 传到手机，允许该文件来源安装应用并完成安装，即可打开研迹。该步骤生成支持局域网 HTTP 调试的 Android 安装包，用于本地部署和评测。

也可以用 USB 安装：手机开启开发者选项和 USB 调试，连接电脑并同意调试授权，将 Android SDK 的 `platform-tools` 加入 PATH，然后执行：

```powershell
adb devices
.\deploy\build-mobile.ps1 -ApiBaseUrl "http://192.168.1.100:8000" -Install
```

连接多台设备时增加 `-DeviceId "adb devices 中显示的设备编号"`。API 地址在编译时写入 APK；电脑 IP 或后端端口变化后，需要重新构建并安装，修改文件或重开应用不会自动改变已安装 APK 的接口地址。

如果使用 Android 模拟器，API 地址可改为 `http://10.0.2.2:8000`。当前指南提供 Android 安装流程；iPhone 需要 macOS、Xcode、签名和独立的 iOS 构建流程。

## 7. 部署验收

保持三个服务运行，在第四个 PowerShell 窗口执行：

```powershell
.\deploy\check-deployment.ps1 -DemoLogin
```

脚本检查后端存活、数据库就绪、Agent 存活、MinIO 存活，以及两个演示账号的登录和个人信息读取。未导入演示数据时省略 `-DemoLogin`。健康检查通过后，在手机上完成以下操作：

1. 使用演示学生登录，确认能查看阶段和“演示 阅读相关论文”任务。
2. 使用演示导师登录，确认能查看演示学生及其课题组关系。
3. 在资料模块上传一份 PDF 或 DOCX 科研资料，等待解析完成并确认授权，然后针对该资料提交科研问答，等待 Agent 完成并查看答案。这一步确认模型 Key、网络、额度及服务间调用可用；Agent 健康接口本身不验证模型调用。缺少授权资料时，问答可能正常返回补充资料提示，不能据此认定模型调用已通过。
4. 上传一个小文件并重新打开，确认 MinIO 的保存和读取正常。
5. 停止并重新启动业务后端，重新登录并确认数据仍然存在。

以上步骤完成后即可使用自己的账号开展任务、周报、科研记录和 AI 辅助工作。

## 8. 更新、备份与常见问题

更新时在 `yanji` 仓库根目录执行 `git pull --ff-only`，停止 Java 服务，备份数据库后运行 `migrate-database.ps1` 和 `build.ps1`，再启动服务；客户端有更新时重新构建并安装 APK。存在自己修改的源码时先保存修改再更新。

数据库和附件分别保存在 MySQL 数据目录与 `backend-java/deploy/runtime/minio-data`。备份时同时保留数据库、MinIO 文件目录和本机私有配置。可在 MySQL 安装目录的命令行中执行下列数据库备份命令，`-p` 会提示输入密码；`--result-file` 避免 PowerShell 重定向改变 SQL 编码：

```powershell
mysqldump --host=127.0.0.1 --port=3306 --user=root -p --single-transaction --no-tablespaces --default-character-set=utf8mb4 --result-file=yanji-backup.sql yanji
```

文件存储目录应在停止 MinIO 后复制备份。私有配置、数据库备份和真实科研附件由使用者自行保存；GitHub 中提供的 SQL 是公开演示数据。

| 现象 | 处理方式 |
| --- | --- |
| 提示找不到 Java、Maven、Flutter、MySQL | 检查安装和 PATH，重新打开 PowerShell；MySQL 和 MinIO 可直接填写完整程序路径 |
| MySQL `Access denied` | 检查 root 密码；应用账号已存在时，核对本地配置里的应用账号密码 |
| 后端无法启动 | 查看该窗口报错和 `backend-java/logs/backend-java.log`，检查数据库、端口、配置和迁移是否完成 |
| 上传附件失败 | 确认 MinIO 已启动并运行过 `init-storage.ps1`；检查文件大小与存储账号配置 |
| 手机连接失败 | 先用手机浏览器访问健康接口，检查同一 Wi-Fi、实际 IPv4、防火墙、网络隔离和 APK 编译地址 |
| AI 请求失败或排队后失败 | 检查 Agent 窗口、完整任务接收地址、百炼 Key、模型名称、额度与互联网连接 |
| 确认配置后手机仍访问旧地址 | 用正确 `-ApiBaseUrl` 重新构建并安装 APK |
| 校验提示迁移文件已改变 | 恢复已经执行的 SQL 版本，再通过新的迁移文件升级；不要直接删除迁移记录绕过检查 |

后端日志位于 `backend-java/logs/`；MinIO 与 Agent 的运行日志显示在各自启动窗口。仓库中的 `backend-java/deploy/` 是本指南所有部署命令的入口。
