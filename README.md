# SmartAlbum 智能相册

在地图上查看你所有照片的拍摄位置。读取本地照片的 EXIF GPS 信息，按位置自动分组，支持轨迹回放、时间轴视图、主题切换。

## 功能

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

## 技术栈

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

## 编译准备

### 1. 申请高德地图 Key

本项目使用高德地图 SDK，你需要自己申请一个免费 Key：

1. 打开 https://lbs.amap.com/ 注册开发者账号
2. 创建应用，添加 Android 平台 Key（绑定你自己的包名 `com.example.smartalbum` 和 SHA-1 签名指纹）
3. 打开 `app/src/main/AndroidManifest.xml`，把 `YOUR_AMAP_API_KEY` 替换成你申请到的 Key

### 2. 配置 local.properties（可选）

项目根目录的 `local.properties`（已被 .gitignore 忽略）可放本地配置：

```properties
# 你的 Android SDK 路径
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
# 位置上报接口 token（不需要后端功能可留空）
SA_API_TOKEN=
```

### 3. 编译

```bash
# Android Studio 直接打开项目根目录即可

# 命令行编译 Debug APK
./gradlew assembleDebug
```

编译产物在 `app/build/outputs/apk/debug/`。

## 目录结构

```
smartalbum-public/
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

## 说明

- 本开源版**不内置后端服务器**。位置上报、自动更新等联网功能默认指向占位地址，不配置后端时这些功能静默不工作，核心的"地图浏览本地照片"功能完全可用。
- `AdminActivity` 管理面板在 Manifest 中已声明，但开源版移除了隐藏的激活码入口。如需使用，可自行在设置页加按钮跳转。
- 本项目为个人兴趣项目，仅供学习交流。

## License

[MIT](LICENSE)
