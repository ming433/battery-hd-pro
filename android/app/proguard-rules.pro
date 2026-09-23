# Battery HD Pro — 混淆规则
#
# 原则：凡是"靠字符串/反射/XML 与系统交互"的都要保留，
# 否则 release 包会出现「debug 正常、release 崩溃/功能失效」。

# ---------------------------------------------------------------- 埋点 SDK
# 事件名、属性名以字符串常量下发，类本身不能被重命名或删除（否则字典对不上）。
-keep class com.batteryhd.analytics.Dictionary { *; }
-keep class com.batteryhd.analytics.Dictionary$** { *; }
# Models 会被 org.json 手工序列化，字段名不能变
-keepclassmembers class com.batteryhd.analytics.** {
    public <fields>;
}

# ---------------------------------------------------------------- 广告（AdMob）
# AdMob 通过反射实例化自定义 Native 模板与中介适配器
-keep public class com.google.android.gms.ads.** { public *; }
-keep class com.google.ads.** { public *; }

# ---------------------------------------------------------------- Play 结算
-keep class com.android.billingclient.api.** { public *; }

# ---------------------------------------------------------------- Android 组件
# Manifest 里声明的组件按类名被系统反射创建，必须保留无参构造
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service

# ---------------------------------------------------------------- 序列化
# SharedPreferences / Bundle 读写不依赖序列化，但 Gson 类的字段若未来引入需保留
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------------------------------------------------------------- 调试信息
# 保留行号：Crashlytics/Play Console 的堆栈需要能映射回源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
