# ===== R8/ProGuard 保留规则 =====

# ---- Room 实体（DAO 生成代码与运行时反射访问字段，需保留） ----
-keep class com.example.heartratecomparison.data.RecordSession { *; }
-keep class com.example.heartratecomparison.data.HrSample { *; }
-keep class com.example.heartratecomparison.data.BatterySnapshot { *; }
-keep class com.example.heartratecomparison.data.SessionWithCount { *; }

# ---- 实现 Serializable 的 UI 模型（保留全部成员） ----
-keep class com.example.heartratecomparison.model.UiDeviceState { *; }
