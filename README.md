### 使用方法简介
由于配置文件存在本地，需要读写存储权限

`MainActivity`中有如下配置，会根据输入将配置写到`/sdcard/config.properties`路径下，也可以手动编辑，除了目标包名，其它配置多个路径可以通过`,`间隔

白名单和黑名单只是不做主动调用，但是仍然会进行dump

<img width="349" height="253" alt="image-20260406190839492" src="https://github.com/user-attachments/assets/8db44bde-9699-4844-962b-64b7e710d148" />


* targetApp：目标APP包名
* targetClass：指定要脱的壳的包路径`com.xxx`（不设置可能会导致 JNI 引用过多，通常建议以App的包名前两层作为筛选）
* blackListClass：填写某些导致App崩溃的类，如果崩溃则需要通过`JDex2 Debugger`的日志对某些类或者包进行筛选（由于写到本地性能开销过大所以没有写）
* Debugger：输出完成主动调用的类列表（`tag~=JDex2 Debugger`）
  * 当对某些APP脱壳过程中出现崩溃的情况，需要参考主动调用的类列表进行分析，可能是因为某些Android的系统类在低版本下不存在，而APP的业务逻辑中做了定义但不会在低版本Android调用
  * 比如某APP的`com.xxx.xxx.conferencesw`包下的类
* Hook：使用Hook方式脱壳（不推荐使用）
* innerClassesFilter：由于可能导致 JNI 引用数量过多，可以尝试关闭对内部类的主动调用，但是对某些壳，脱出来的匿名类依旧是空的，按需开启，建议使用黑名单过滤而不是本配置

如果使用的是Lsposed，**记得在Lsposed中勾选对应APP**

脱壳效果：
<img width="1799" height="1090" alt="image-20260406203358574" src="https://github.com/user-attachments/assets/37eb0074-19e1-4c29-84cc-3f0173bd19bf" />

### 本工具的局限性

* 只能对抗类级别的方法抽取，而基于方法粒度的抽取则会无法进行，并且无法应对方法执行结束后重新抽取的情况，还有真正开始执行字节码才动态解密的情况
* 各个方面的便捷性不足，一些崩溃问题可能需要参考崩溃日志进行分析，而且不算很完善
* 基于Android9.0+开发，未适配Android7系列及以下，Android8稳定性未知

* JNI全局引用数量超过最大上限 51200 个导致崩溃
  * 如果类的数量过于庞大，由于每次主动调用每个类都创建一个新的匿名 `XC_MethodHook` 实例，即使使用了`XC_MethodHook`共享实例，但是只是减少了 callback 对象的创建，每次 `hookAllConstructors `底层仍然会创建全局引用。所以这种情况下要么选择过滤内部类，要么**使用黑名单去跳过之前dump过的包**（个人推荐），或者使用白名单每次只dump几个包下的类
* 高度依赖于Lsposed的隐蔽性，如果Lsposed被检测则会直接闪退无法进行脱壳
* 对于一些该系统下不存在的类的主动调用实例化可能导致崩溃，需要通过观察`invokeDebugger`的结束类去将其加入黑名单
* ~~UI写的有点草率~~

