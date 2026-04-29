# 告诉 R8 保留 SPI 接口本身
-keep interface com.lin.router.api.LinRouterLoader { *; }
-keep interface com.lin.router.api.LinInterceptorLoader { *; }

# 告诉 R8 保留所有实现了这两个接口的实现类（也就是 KSP 生成的类），并保留它们的无参构造函数
-keep class * implements com.lin.router.api.LinRouterLoader { <init>(); }
-keep class * implements com.lin.router.api.LinInterceptorLoader { <init>(); }

# 保留 KSP 自动生成的参数注入类及其无参构造 (如果是通过反射寻找的话)
-keep class * implements com.lin.router.api.LinRouterInjector { <init>(); }
