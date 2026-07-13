# giants-analyse
[![Maven Central](https://img.shields.io/maven-central/v/com.github.vencent-lu/giants-analyse.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.github.vencent-lu/giants-analyse)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![JDK](https://img.shields.io/badge/JDK-1.7%2B-orange.svg)](https://www.oracle.com/java/)

JAVA 代码执行时间分析工具类。

`giants-analyse` 通过 **Servlet Filter** 与 **AOP 拦截器** 两种方式，采集一次 HTTP 请求或一次方法调用在整个调用栈上各环节的耗时，并以**树形结构**日志输出，帮助快速定位性能瓶颈。计时数据基于 `ThreadLocal`，天然线程安全。

> 📖 完整用户手册见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)，包含核心概念、集成示例、日志解读、最佳实践与 FAQ。

## 快速开始

### 1. 引入依赖

```xml
<dependency>
  <groupId>com.github.vencent-lu</groupId>
  <artifactId>giants-analyse</artifactId>
  <version>1.0.8</version>
</dependency>
```

运行环境：JDK 1.7+、Servlet 3.0.1+（使用 Filter 时）、AspectJ（使用 AOP 时）、SLF4J 日志实现。

### 2. 注册组件（Filter 建根 + AOP 补栈，推荐）

```xml
<!-- Filter 以「一次 HTTP 请求」为根，负责输出完整时间树 -->
<bean id="executionTimeProfilerFilter"
      class="com.giants.analyse.filter.ExecutionTimeProfilerFilter">
  <property name="logCallStackTimeAnalyse" value="true"/>
  <property name="threshold" value="500"/>
</bean>

<!-- AOP 只把方法追加为子节点，不单独输出日志 -->
<bean id="methodProfilerAop" class="com.giants.analyse.aop.EnterExecutionTimeProfilerAop">
  <property name="logCallStackTimeAnalyse" value="false"/>
</bean>

<aop:config>
  <aop:aspect ref="methodProfilerAop">
    <aop:pointcut id="allLayers"
                  expression="execution(* com.yourapp..controller..*.*(..)) or
                              execution(* com.yourapp..service..*.*(..)) or
                              execution(* com.yourapp..dao..*.*(..))"/>
    <aop:around pointcut-ref="allLayers" method="timerProfiler"/>
  </aop:aspect>
</aop:config>
```

## 组件与参数

### com.giants.analyse.filter.ExecutionTimeProfilerFilter

httpRequest 时间统计过滤器（继承 `com.giants.web.filter.AbstractFilter`），以「一次 HTTP 请求」为根 Entry。参数说明：

* `logCallStackTimeAnalyse`
    * 类型 : boolean
    * 默认值 : false
    * 说明 : 是否记录方法栈执行时间日志。为 `false` 时该 Filter 仅透传请求，不计时、不输出。
* `threshold`
    * 类型 : int
    * 单位 : ms
    * 默认值 : 500
    * 说明 : 执行时间阈值，≤ 阈值打印 `info` 日志，> 阈值打印 `warn` 日志，异常打印 `error` 日志
* `thresholdPropertiesPath`（Filter `init-param`）
    * 类型 : String
    * 说明 : 可选。指定 classpath 下的 properties 文件路径，从中读取键 `profiler.executionTime.threshold` 作为阈值

### com.giants.analyse.aop.EnterExecutionTimeProfilerAop

方法执行时间统计 AOP 拦截器（环绕通知织入方法为 `timerProfiler`）。参数说明：

* `logCallStackTimeAnalyse`
    * 类型 : boolean
    * 默认值 : false
    * 说明 : 是否由本拦截器充当「根 Entry」并输出日志。为 `false`（或当前线程已存在根 Entry）时，仅把方法作为子节点追加到已有时间树上
* `threshold`
    * 类型 : int
    * 单位 : ms
    * 默认值 : 500
    * 说明 : 执行时间阈值，日志级别规则同 Filter（仅当本拦截器充当根时生效）
* `showArguments`
    * 类型 : boolean
    * 默认值 : false
    * 说明 : 是否记录请求参数（方法实参的 `toString` 形式）

### com.giants.analyse.profiler.ExecutionTimeProfiler

底层计时引擎，纯静态工具类，计时状态存于 `ThreadLocal`。可在业务代码中直接使用：`start` / `enter` / `release` / `reset` / `getDuration` / `dump`。详见用户手册的 [API 速查表](docs/USER_GUIDE.md#8-api-速查表) 与[手动编码调用](docs/USER_GUIDE.md#44-手动编码调用)示例。

## 日志输出示例

```
2021-11-01 17:36:44.770  INFO - [TID: N/A] - 21 --- [nio-9001-exec-8] c.g.a.f.ExecutionTimeProfilerFilter      : Response of POST /systemModule/search returned in 63ms
Detail: 0 [63ms (1ms), 100%] - process HTTP request
        +---0 [22ms, 35%, 35%] - com.giants.auth.authority.api.AuthorityService.loadLoginEmployee
        `---22 [40ms, 63%, 63%] - com.giants.auth.gateway.conf.controller.SystemModuleController.search
            +---22 [19ms, 48%, 30%] - com.giants.auth.authority.api.AuthorityService.checkControllerAuthority
            `---41 [21ms, 52%, 33%] - com.giants.auth.conf.api.SystemModuleService.searchSystemModules
```

每个节点格式为 `相对起始时间 [总耗时 (自身耗时), 父占比, 全局占比] - 描述`。排查性能问题时，重点关注**自身耗时占比高**的节点。字段的完整解读见 [日志输出解读](docs/USER_GUIDE.md#5-日志输出解读)。

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)
