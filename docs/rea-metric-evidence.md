# RideHUD × 运动健康 APK — REA 证据笔记

> 目标：对齐 RideHUD 骑行 HUD 字段的公式 / 阈值 / JSON 键。  
> 更新：2026-10-30  
> 实现常量：`RideHudDefaults` / `CalorieEstimator`（`ridehud-v3-speed-scaling-bike`）

完整分节原文见对话粘贴；本文件保留实现向摘要。

---

## 落地表

| 项 | 代码 | 状态 |
|---|---|---|
| bike auto-pause/resume | `BikeAutoPauseResumeStrategy` | ✅ |
| AR still 10s | Service | ✅ |
| altitude 0.6 m | `ElevationTracker` | ✅ |
| TIME 排除暂停 | `elapsedMs` | ✅ |
| avg = dist/运动时间 | `RideHudDefaults.avgSpeedKmh` | ✅ |
| max 每 5s | `RideRecorder` | ✅ |
| Speed 10× clean | `GpsTracker.cleanSpeedKmh` | ✅ |
| speed-scaling 卡路里 | `CalorieEstimator` | ✅ |
| activeCalorie | `activeCaloriesKcal` | ✅ |
| ACSM MET=8 helper | `acsmBikeKcal` | ✅ |
| slope 设备算式 | — | ❌ |
| 表透传卡路里 | — | ❌ |
| 踏频 | — | ❌ |

常量入口：`app/src/main/java/zj/cursor/ridehud/domain/RideHudDefaults.kt`
