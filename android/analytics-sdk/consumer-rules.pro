# 传给使用方的混淆规则（本 SDK 无反射/注解处理，仅需保留字典常量便于排障）
-keep class com.batteryhd.analytics.Dictionary { *; }
-keep class com.batteryhd.analytics.Dictionary$** { *; }
