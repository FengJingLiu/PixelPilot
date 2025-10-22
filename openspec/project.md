# Project Context

## Purpose
- PixelPilot 是一款开源 Android FPV 应用，目标是“即插即用、极低延迟”的视频传输体验。
- 通过 wfb-ng 与用户态 rtl8812au 驱动（devourer）实现 5GHz 低延迟链路；内置 OSD（屏显）与 MAVLink 遥测展示。
- 支持 DVR（本地录像）、蓝牙转发器、VR 模式、实时链路统计，并提供简洁的 UI 以快速起飞。

## Tech Stack
- Android（Java 17，AndroidX，Material Components）
  - Android Gradle Plugin: 8.5.2（gradle/libs.versions.toml:1; gradle/libs.versions.toml:16）
  - Compile/Target SDK: 34（app/build.gradle:9 app/build.gradle:16）
  - Min SDK: 26（app/build.gradle:14）
  - ViewBinding: 启用（app/build.gradle:39）
- 原生模块（NDK + CMake）
  - NDK: 26.1.10909125（app/build.gradle:44）
  - CMake 参与构建 `:app:videonative`、`:app:wfbngrtl8812`、`:app:mavlink`（app/*/build.gradle）
  - 主要语言：C/C++（见各模块 src/main/cpp/CMakeLists.txt）
- 模块结构（settings.gradle）
  - `:app`（应用壳）
  - `:app:videonative`（视频解码/渲染，基于 LiveVideo10ms）
  - `:app:wfbngrtl8812`（Wi‑Fi 链路与统计，集成 wfb-ng 与 devourer）
  - `:app:mavlink`（MAVLink 解析与 JNI 桥接）
- 依赖与仓库
  - Repositories: google, mavenCentral, jitpack（settings.gradle: dependencyResolutionManagement）
  - 第三方：MPAndroidChart（libs.versions.toml: philjay-mpandroidchart）、AndroidX 基础库

## Project Conventions

### Code Style
- Java：遵循标准 Android 风格，4 空格缩进；使用 ViewBinding（`ActivityVideoBinding`）而非 `findViewById`；类名 PascalCase，方法/字段 camelCase。
- C/C++：项目根提供 `.clang-format`（LLVM 基准；IndentWidth=4；ColumnLimit=120；AlignTrailingComments=true 等，.clang-format:1）统一原生代码风格。
- 资源与包名：包名 `com.openipc.pixelpilot`（app/build.gradle:11），资源命名保持语义化（布局、xml 配置等）。

### Architecture Patterns
- App 层：以 `VideoActivity` 为核心（app/src/main/java/com/openipc/pixelpilot/VideoActivity.java），聚合视频播放、OSD、MAVLink 更新、蓝牙转发与 DVR 控制；使用 `Handler` 周期性驱动本地回调（100ms）。
- Link/Device：`WfbLinkManager` 管理 USB 权限与适配器绑定；`WfbNgVpnService` 为链路提供 VpnService 能力（如需 TUN/TAP）。
- OSD：`OSDManager` + `OSDElement` 负责叠加与布局；部分 UI 使用 MPAndroidChart 展示信号状态。
- 原生库：通过 CMake/JNI 暴露 `VideoPlayer`、`MavlinkNative` 等能力，Java 侧以接口回调（如 `IVideoParamsChanged`）感知参数变更。
- 构建与发布：GitHub Actions 在 `master` 和语义化标签上构建发布 APK（.github/workflows/build.yml）。

### Testing Strategy
- 当前未配置单元测试/仪器测试；主要依赖真实设备手动验证（README.md 提供已测试设备清单）。
- 建议后续补充：
  - 原生层最小单元测试（videonative/src/main/cpp/tests/CMakeLists.txt 已存在样例入口）
  - 基于 Espresso/UIAutomator 的关键路径冒烟用例（启动、播放、录像切换）
  - 构建后脚本校验 APK 架构/权限与基础启动

### Git Workflow
- 主分支：`master`；发布：语义化标签 `vX.Y.Z` 与 `latest`（.github/workflows/build.yml）。
- CI：JDK 17、Gradle 缓存、签名文件从 GitHub Secrets 注入并打包 `PixelPilot.apk`。
- 子模块：`devourer` 与 `wfb-ng` 作为子模块引入（.gitmodules）。克隆后需 `git submodule update --init --recursive`。
- 提交规范：目前未强制工具检查（无 spotless/checkstyle 配置）；建议遵循“动词开头的英文短句 + 关联模块”风格。

## Domain Context
- FPV/无人机视频传输：通过 wfb-ng 在 5GHz 环境下提供低延迟码流；依赖支持的 rtl8812au 适配器（支持列表见 `app/src/main/res/xml/usb_device_filter.xml`）。
- 遥测：解析 MAVLink，显示基本飞行数据与链路质量；OSD 可展示图表与文本元素。
- DVR：支持将视频流保存到 `Movies/`；后续计划支持音频合流（README.md 列为潜在改进）。
- VR/双目：提供 VR 模式开关；蓝牙外设可转发控制数据。
- 安全：内置 `gs.key`，也可从用户存储中选择不同 key（README.md）。

## Important Constraints
- 性能敏感：端侧解码与渲染需尽量减少拷贝与延迟，性能强弱对体验影响显著（README.md 明示）。
- 平台与版本：Min SDK 26，Target/Compile SDK 34；仅 arm64-v8a/armeabi-v7a（README.md）。
- 原生构建：受 NDK/CMake 版本约束；构建脚本使用 `buildToolsVersion '35.0.0'`（app/build.gradle:41）。
- 权限：USB、蓝牙、存储、VPN 等权限需按 Android 版本动态声明与请求。
- 兼容性与稳定性：40MHz 带宽存在不稳定（README.md 标注），音频流仍在完善中。

## External Dependencies
- Git 子模块
  - `openipc/devourer`（app/wfbngrtl8812/src/main/cpp/devourer，.gitmodules:1）
  - `svpcom/wfb-ng`（app/wfbngrtl8812/src/main/cpp/wfb-ng，.gitmodules:6）
- Maven 依赖
  - `com.github.PhilJay:mpandroidchart`（gradle/libs.versions.toml:12）
  - AndroidX: appcompat、material、constraintlayout、core-ktx 等
- 构建/发行
  - GitHub Actions：签名文件通过 Secrets 注入；自动产出并上传 `PixelPilot.apk`
