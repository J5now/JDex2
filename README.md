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
