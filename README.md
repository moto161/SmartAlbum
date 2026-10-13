# SmartAlbum 智能相册

> 在地图上重温你每一次按下快门的地点。SmartAlbum 读取本地照片的 EXIF GPS 信息，按拍摄位置自动聚类，并在高德地图上以标记点展示，帮你用「空间」维度重新组织回忆。

## 📑 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [开源版说明](#开源版说明)
- [目录结构](#目录结构)
- [参与贡献](#参与贡献)
- [已知限制](#已知限制)
- [许可证](#许可证)

## ✨ 功能特性

- 🗺️ **地图浏览**：在高德地图上以标记点展示所有带 GPS 信息的照片
- 📍 **位置分组**：自动按拍摄位置聚类分组（网格分桶 + 并查集，O(n) 复杂度）
- 🎞️ **照片详情**：点标记点查看该位置所有照片网格，支持查看大图
- 🛤️ **轨迹回放**：将一段时间内的位置点连成轨迹，支持速度调节播放
- ⏱️ **时间轴视图**：按时间顺序浏览照片
- 🔍 **地点搜索**：搜索 POI 后地图自动跳到街道级（zoom=17）
- 🎨 **多主题**：清新白等多套主题
- 💾 **三层缓存**：内存 → SQLite → 外部 JSON（卸载重装可恢复）
- 🌐 **坐标转换**：WGS84 ↔ GCJ02 自动转换
- 🧪 **79 项单元测试** 覆盖核心扫描、分组、数据库迁移逻辑

## 🛠 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | ViewBinding + Fragment + Material Components |
| 地图 | 高德地图 SDK（map2d 6.0.0 / search 9.7.0 / location 6.2.0） |
| 本地存储 | SQLite（手写 PhotoDatabaseHelper，26+ Migration） |
| 图片加载 | Glide 4.16 |
| 依赖注入 | Hilt 2.48 |
| 异步 | Kotlin Coroutines / Flow |
| 最低版本 | minSdk 27（Android 8.1）/ targetSdk 34 |

## 🚀 快速开始

### 环境要求

- **JDK 17**（编译必需）
- **Android SDK**：Android 平台 34 + 对应 build-tools
- **Android Studio**（推荐）或命令行 Gradle

### 1. 获取代码

```bash
git clone https://github.com/moto161/SmartAlbum.git
cd SmartAlbum
```

### 2. 申请高德地图 Key

本项目使用高德地图 SDK，你需要自己申请一个免费 Key：

1. 打开 https://lbs.amap.com/ 注册开发者账号
2. 创建应用，添加 Android 平台 Key（绑定你自己的包名 `com.example.smartalbum` 和 SHA-1 签名指纹）
3. 打开 `app/src/main/AndroidManifest.xml`，把 `YOUR_AMAP_API_KEY` 替换成你申请到的 Key

> 没有 Key 也能编译，但地图功能无法正常加载。

### 3. 配置 local.properties（可选）

项目根目录的 `local.properties`（已被 `.gitignore` 忽略，不会进入版本库）可放本地配置：

```properties
# 你的 Android SDK 路径（通常 Android Studio 会自动生成）
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk

# 位置上报接口 token（不需要后端功能可留空）
SA_API_TOKEN=
```

### 4. 编译运行

- **Android Studio**：打开项目根目录，连接设备或启动模拟器，点击 ▶ Run 即可。
- **命令行**：

```bash
# 编译 Debug APK
./gradlew assembleDebug
```

编译产物在 `app/build/outputs/apk/debug/`。

### 5. 运行测试

```bash
# 运行 79 项单元测试
./gradlew testDebugUnitTest
```

## 📢 开源版说明

本仓库为 **开源版**，与作者自用版的主要差异：

- **不内置后端服务器**。位置上报、自动更新等联网功能默认指向占位地址（`https://your-api.example.com` / `https://your-update.example.com`）；不配置后端时这些功能静默不工作，但核心的「地图浏览本地照片」完全可用。
- **已脱敏**：高德 Key、接口 Token、服务器地址等敏感配置统一由 `local.properties` 注入，**不会进入版本库**（已被 `.gitignore` 忽略）。
- **已移除隐藏入口**：`AdminActivity` 管理面板在 Manifest 中仍声明，但开源版移除了隐藏的激活码入口；如需使用，可自行在设置页加按钮跳转。

> 如需启用位置上报 / 自动更新，请自行部署后端，并将 `app/build.gradle` 中的 `API_BASE_URL` / `UPDATE_BASE_URL` 改为你的域名，同时在 `local.properties` 填入 `SA_API_TOKEN`。

## 📂 目录结构

```
SmartAlbum/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/smartalbum/
│   │   │   ├── data/          # 扫描、仓库、数据库
│   │   │   ├── ui/
│   │   │   │   ├── map/       # 地图页
│   │   │   │   ├── list/      # 位置列表
│   │   │   │   ├── gallery/   # 照片网格/详情
│   │   │   │   ├── settings/  # 设置页
│   │   │   │   ├── admin/     # 管理面板（需自行加入口）
│   │   │   │   └── trail/     # 轨迹回放
│   │   │   ├── api/           # 位置上报 API 客户端
│   │   │   ├── service/       # 后台定位服务
│   │   │   └── util/          # 工具类
│   │   └── res/
│   └── build.gradle
└── build.gradle
```

## 🤝 参与贡献

欢迎 Issue 与 Pull Request。提交前请确认：

1. `./gradlew testDebugUnitTest` 全部通过（79 项单元测试）；
2. 提交信息简洁、按逻辑分组；
3. **不要提交任何密钥、Token 或个人服务器地址**——敏感配置一律走 `local.properties`。

> 本仓库暂未配置 Issue / PR 模板，按需自由提交即可。

## ⚠️ 已知限制

- 仅收录**带 GPS 坐标**的照片；无坐标的照片不会出现在地图 / 位置分组中。
- 首次启动会扫描媒体库并做逆地理编码，照片较多时耗时较长，属正常现象。
- 轨迹回放依赖位置上报后端数据，开源版不含后端，需配合自建服务才能使用。
- 当前未开启 `shrinkResources` / R8 严格混淆，后续可优化包体。

## 📄 许可证

[MIT](LICENSE)。本项目为个人兴趣项目，仅供学习交流。
