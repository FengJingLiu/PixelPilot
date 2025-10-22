## ADDED Requirements

### Requirement: DVR 音频合流录制
应用在录像时 SHALL 将音频轨与视频轨一并保存到同一文件。

#### Scenario: 开启音频录制
- WHEN 用户在设置中启用“录像包含音频”
- AND 当前会话有可用的音频流
- THEN 新生成的录像文件包含 1 条视频轨 + 1 条音频轨

#### Scenario: 音频不可用时的降级
- WHEN 无可用音频或解码初始化失败
- THEN 录像自动降级为仅视频，并通过 UI 提示用户

#### Scenario: 权限不足
- WHEN 应用无写入目标目录权限
- THEN 引导用户授予权限；若拒绝，录像中止并提示

#### Scenario: 回放兼容性
- WHEN 用户使用常见播放器（系统播放器或 VLC）打开录像
- THEN 能正确回放音视频且音画同步在容忍范围内
