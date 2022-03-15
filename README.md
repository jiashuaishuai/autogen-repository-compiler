# autogen-repository-compiler
# 废弃，因为在获取基本类型时，apt获取到的是java包下的基本类型，kotlin中无法引用java.lang.String 等等
错误日志：
```java
Type mismatch.
 Required: java.lang.String
 Found:    kotlin.String
```
## 解决方案：
将：https://github.com/JetBrains/kotlin/blob/master/core/metadata.jvm/src/org/jetbrains/kotlin/metadata/jvm/deserialization/ClassMapperLite.kt
进行逆转，由于使用嵌套泛型太多，Map，List，Set等集合不方便一层一层转换
## 参考：https://johnsonlee.io/2021/10/29/do-you-really-know-kotlin-1/
