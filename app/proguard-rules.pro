# 落笔：Room 实体 / DAO 与 WorkManager Worker 保留类名
-keep class com.scarlett.luobi.** extends androidx.work.ListenableWorker { <init>(...); }
-keep class com.scarlett.luobi.Note { *; }
-keep class com.scarlett.luobi.Template { *; }
-keep class com.scarlett.luobi.CleanTpl { *; }
-keep class com.scarlett.luobi.Channel { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
