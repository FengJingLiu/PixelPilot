## Why
当前 DVR 仅保存视频轨，不包含音频。用户希望录像文件内含音视频（便于回放与分析）。

## What Changes
- 新增“录像包含音频”能力（默认开启，可在设置中切换）
- 解析摄像端 opus 音频流并与视频合流写入容器（MP4，保留现有视频编码）
- 权限与错误处理：无写入权限或音频不可用时，自动降级为纯视频，并给予 UI 提示
- UI：设置项与录像指示同步显示“含音频”状态

## Impact
- 受影响规格：`specs/recording/spec.md`
- 受影响代码：
  - Java：`app/src/main/java/com/openipc/pixelpilot/VideoActivity.java`（录像/权限/UI）
  - 原生：`videonative`（如需音频管线/封装器）、`mavlink`（无直接影响）
  - 构建：无（保持现有 NDK/CMake 配置）
