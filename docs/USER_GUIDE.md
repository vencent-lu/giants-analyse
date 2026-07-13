# giants-analyse 用户使用手册

> 版本：1.0.8 ｜ JAVA 代码执行时间分析工具

`giants-analyse` 是一个轻量级的 JAVA 代码执行时间分析（Profiling）工具库。它通过 **Servlet Filter** 与 **AOP 拦截器** 两种方式，采集一次 HTTP 请求或一次方法调用在整个调用栈上各个环节所消耗的时间，并以**树形结构**的日志输出，帮助你快速定位性能瓶颈。

---

## 目录

- [1. 核心概念](#1-核心概念)
- [2. 安装与依赖](#2-安装与依赖)
- [3. 组件详解](#3-组件详解)
  - [3.1 ExecutionTimeProfiler（核心计时器）](#31-executiontimeprofiler核心计时器)
  - [3.2 ExecutionTimeProfilerFilter（HTTP 请求过滤器）](#32-executiontimeprofilerfilterhttp-请求过滤器)
  - [3.3 EnterExecutionTimeProfilerAop（方法 AOP 拦截器）](#33-enterexecutiontimeprofileraop方法-aop-拦截器)
- [4. 集成方式](#4-集成方式)
  - [4.1 仅使用 Filter](#41-仅使用-filter)
  - [4.2 仅使用 AOP](#42-仅使用-aop)
  - [4.3 Filter + AOP 组合（推荐）](#43-filter--aop-组合推荐)
  - [4.4 手动编码调用](#44-手动编码调用)
- [5. 日志输出解读](#5-日志输出解读)
- [6. 最佳实践](#6-最佳实践)
- [7. 常见问题 FAQ](#7-常见问题-faq)
- [8. API 速查表](#8-api-速查表)

---

## 1. 核心概念

### 计时条目（Entry）

工具内部把每一段被计时的代码抽象成一个 **Entry（计时条目）**。多个 Entry 之间存在父子关系，最终构成一棵**调用时间树**：

- **根 Entry**：一次分析的起点，通常是「处理一次 HTTP 请求」或「第一个被拦截的方法」。
- **子 Entry**：在根 Entry 计时期间被触发的下层调用，例如根方法内部调用的 Service、DAO 方法。
- 每个 Entry 都记录了：起始时间、结束时间、总耗时、自身耗时（总耗时减去子 Entry 耗时）、在父 Entry 中的占比、在整棵树中的占比。

### 线程隔离（ThreadLocal）

计时数据保存在 `ThreadLocal` 中，因此**天然线程安全**：每个请求线程各自维护自己的调用时间树，互不干扰。这也意味着分析工具**不能跨线程使用**——如果业务逻辑切换了线程（例如异步任务、线程池），子线程中的计时不会自动挂到父线程的树上。

### 两种工作模式

| 模式 | 谁来创建「根 Entry」 | 适用场景 |
| --- | --- | --- |
| Filter 驱动 | `ExecutionTimeProfilerFilter` 以「一次 HTTP 请求」为根 | Web 应用，需要观测整条请求链路 |
| AOP 驱动 | `EnterExecutionTimeProfilerAop` 以「第一个被拦截的方法」为根 | 非 Web 场景、定时任务、只关心某层方法 |

两者可以组合：由 Filter 创建根，AOP 拦截器只负责在树上**追加子节点**（见 [4.3](#43-filter--aop-组合推荐)）。

---

## 2. 安装与依赖

### Maven 坐标

```xml
<dependency>
  <groupId>com.github.vencent-lu</groupId>
  <artifactId>giants-analyse</artifactId>
  <version>1.0.8</version>
</dependency>
```

### 运行环境要求

- **JDK**：1.7 及以上（本库以 `source/target = 1.7` 编译）。
- **Servlet API**：3.0.1+（仅使用 Filter 时需要；库中该依赖为 `provided`，由 Web 容器提供）。
- **AspectJ**：`aspectjweaver`（使用 AOP 时需要，库已传递引入）。
- **日志**：基于 SLF4J 门面，需自行提供实现（Logback / Log4j2 等）。

### 传递依赖

本库会传递引入以下依赖，通常无需手动声明：

- `com.github.vencent-lu:giants-common`（提供 `ReflectUtils` 等反射工具）
- `com.github.vencent-lu:giants-web`（提供 `AbstractFilter` 过滤器基类）
- `org.aspectj:aspectjweaver`

---

## 3. 组件详解

### 3.1 ExecutionTimeProfiler（核心计时器）

`com.giants.analyse.profiler.ExecutionTimeProfiler`

这是整个库的底层引擎，是一个纯静态工具类，所有计时状态存放在 `ThreadLocal` 中。Filter 与 AOP 都是对它的封装，你也可以在业务代码里直接调用。

核心方法：

| 方法 | 说明 |
| --- | --- |
| `start()` / `start(String message)` | 开始计时，创建根 Entry。`message` 为该 Entry 的描述。 |
| `enter(String message)` | 在当前未结束的 Entry 下新增一个子 Entry 并开始计时。 |
| `release()` | 结束「最近一个未结束」的 Entry，记录结束时间。 |
| `reset()` | 清空计时器。清空后必须重新 `start()` 才能再次计时。 |
| `getDuration()` | 取得根 Entry 的总耗时（毫秒）；未开始计时返回 `-1`。 |
| `getEntry()` | 取得根 Entry 对象；不存在返回 `null`。 |
| `dump()` / `dump(prefix)` / `dump(prefix1, prefix2)` | 将整棵时间树格式化为字符串。 |

**调用规则**：`start` 与 `enter` 必须与 `release` 成对出现，且遵循「后进先出」的栈式顺序，最后用 `reset` 清理，否则会造成 Entry 结构错乱或 `ThreadLocal` 残留。推荐把 `release`/`reset` 放在 `finally` 块中。

### 3.2 ExecutionTimeProfilerFilter（HTTP 请求过滤器）

`com.giants.analyse.filter.ExecutionTimeProfilerFilter`（继承自 `com.giants.web.filter.AbstractFilter`）

以「一次 HTTP 请求」为根 Entry，统计整个请求处理链路的耗时。

**参数：**

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `logCallStackTimeAnalyse` | boolean | `false` | 是否开启方法栈耗时分析。**为 `false` 时该 Filter 不做任何计时与日志输出**，仅透传请求。 |
| `threshold` | int（毫秒） | `500` | 耗时阈值。请求耗时 ≤ 阈值输出 `INFO` 日志，> 阈值输出 `WARN` 日志，抛异常输出 `ERROR` 日志。 |

**参数配置方式：**

1. 通过构造函数：`new ExecutionTimeProfilerFilter(true, 500)`。
2. 通过 setter：`setLogCallStackTimeAnalyse(...)`、`setThreshold(...)`（适合 Spring Bean 方式装配）。
3. 通过 Filter 的 `init-param` `thresholdPropertiesPath`：指定一个 classpath 下的 properties 文件路径，从中读取 `profiler.executionTime.threshold` 作为阈值。

> 继承自 `AbstractFilter` 的能力：支持 `findInitParameter` 从 Filter 级 / 全局 `init-param` 查找参数，内置防重入处理，并提供 `eatException`（默认 `true`）等行为。

**行为要点：**

- 只有 `logCallStackTimeAnalyse=true` 时，Filter 才会 `ExecutionTimeProfiler.start("process HTTP request")`，并在请求结束后 `dump` 出时间树、`reset` 清理。
- 无论成功、超时还是异常，都会在 `finally` 中结束计时并输出对应级别日志，然后向上重新抛出原始异常。
- 日志中的请求描述格式为 `HTTP方法 URI?查询串`（由 `dumpRequest` 生成）。

### 3.3 EnterExecutionTimeProfilerAop（方法 AOP 拦截器）

`com.giants.analyse.aop.EnterExecutionTimeProfilerAop`

基于 AspectJ 的**环绕通知（Around）**，织入器方法为 `timerProfiler(ProceedingJoinPoint)`。它能拦截任意 Bean 方法，把方法调用挂到调用时间树上。

**参数：**

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `logCallStackTimeAnalyse` | boolean | `false` | 是否由本拦截器充当「根 Entry」的创建者并输出日志。 |
| `threshold` | int（毫秒） | `500` | 耗时阈值，日志级别规则同 Filter。仅在本拦截器充当根时生效。 |
| `showArguments` | boolean | `false` | 是否在方法描述中打印实参（`toString` 形式）。 |

**核心判定逻辑**（`timerProfiler`）：

```
若 (logCallStackTimeAnalyse == false) 或 (已存在根 Entry)：
    仅 enter/release —— 把当前方法作为子节点追加到已有的时间树上
否则：
    start/release/reset —— 由本方法充当根 Entry，结束时 dump 并输出日志
```

因此：

- **搭配 Filter 使用时**，AOP 拦截器一般设 `logCallStackTimeAnalyse=false`，让它只负责「追加子节点」，由 Filter 统一输出整棵树。
- **单独使用 AOP 时**，需把某一层（通常是 Controller / 入口方法）的拦截器设 `logCallStackTimeAnalyse=true`，让它充当根并输出日志；其余层可复用同一 Bean（因为一旦根已存在，它会自动只做 enter/release）。

**方法名解析：** 拦截器会尽量还原方法所属的**接口名**（通过 `ReflectUtils` 查找），使日志显示 `接口全限定名.方法名` 而非代理类名，可读性更好。

---

## 4. 集成方式

### 4.1 仅使用 Filter

**web.xml 方式：**

```xml
<filter>
  <filter-name>executionTimeProfilerFilter</filter-name>
  <filter-class>com.giants.analyse.filter.ExecutionTimeProfilerFilter</filter-class>
  <!-- 可选：从 properties 读取阈值 -->
  <init-param>
    <param-name>thresholdPropertiesPath</param-name>
    <param-value>profiler.properties</param-value>
  </init-param>
</filter>
<filter-mapping>
  <filter-name>executionTimeProfilerFilter</filter-name>
  <url-pattern>/*</url-pattern>
</filter-mapping>
```

> 注意：通过 web.xml 无标准方式设置 `logCallStackTimeAnalyse`。若需开启方法栈分析，建议用 Spring Bean 方式（下）注册 Filter，或直接使用带参构造函数。

对应的 `profiler.properties`（放在 classpath 根目录）：

```properties
profiler.executionTime.threshold=500
```

**Spring Bean 方式（可完整配置参数）：**

```xml
<bean id="executionTimeProfilerFilter"
      class="com.giants.analyse.filter.ExecutionTimeProfilerFilter">
  <property name="logCallStackTimeAnalyse" value="true"/>
  <property name="threshold" value="500"/>
</bean>
```

再通过 `DelegatingFilterProxy` 将其注册到 Servlet 容器即可。

### 4.2 仅使用 AOP

以 Spring XML 配置为例，让入口层方法充当根 Entry：

```xml
<!-- 拦截器 Bean：入口层，充当根并输出日志 -->
<bean id="rootProfilerAop" class="com.giants.analyse.aop.EnterExecutionTimeProfilerAop">
  <property name="logCallStackTimeAnalyse" value="true"/>
  <property name="threshold" value="500"/>
  <property name="showArguments" value="false"/>
</bean>

<aop:config>
  <aop:aspect ref="rootProfilerAop">
    <!-- 拦截所有 Service 方法 -->
    <aop:pointcut id="servicePointcut"
                  expression="execution(* com.yourapp..service..*.*(..))"/>
    <aop:around pointcut-ref="servicePointcut" method="timerProfiler"/>
  </aop:aspect>
</aop:config>
```

同一个 Bean 织入到多层方法时，第一个进入的方法会创建根，后续方法自动作为子节点追加——无需为每一层单独配置。

### 4.3 Filter + AOP 组合（推荐）

这是最能发挥价值的用法：**Filter 负责根节点与最终输出，AOP 负责补全中间调用栈**。

```xml
<!-- 1. Filter 充当根 -->
<bean id="executionTimeProfilerFilter"
      class="com.giants.analyse.filter.ExecutionTimeProfilerFilter">
  <property name="logCallStackTimeAnalyse" value="true"/>
  <property name="threshold" value="500"/>
</bean>

<!-- 2. AOP 只追加子节点，不输出日志（logCallStackTimeAnalyse=false） -->
<bean id="methodProfilerAop" class="com.giants.analyse.aop.EnterExecutionTimeProfilerAop">
  <property name="logCallStackTimeAnalyse" value="false"/>
  <property name="showArguments" value="false"/>
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

效果：一次请求进入 → Filter 建立根 `process HTTP request` → Controller / Service / DAO 方法逐层挂到树上 → 请求结束时由 Filter 打印完整调用时间树（见 [第 5 节](#5-日志输出解读)）。

### 4.4 手动编码调用

无框架依赖时也能直接使用核心计时器：

```java
ExecutionTimeProfiler.start("批量导入任务");
try {
    ExecutionTimeProfiler.enter("读取文件");
    try {
        readFile();
    } finally {
        ExecutionTimeProfiler.release();
    }

    ExecutionTimeProfiler.enter("写入数据库");
    try {
        writeDb();
    } finally {
        ExecutionTimeProfiler.release();
    }
} finally {
    ExecutionTimeProfiler.release();          // 结束根 Entry
    log.info(ExecutionTimeProfiler.dump("Detail: ", "        "));
    ExecutionTimeProfiler.reset();            // 清理 ThreadLocal
}
```

---

## 5. 日志输出解读

一条典型输出如下：

```
Response of POST /systemModule/search returned in 63ms
Detail: 0 [63ms (1ms), 100%] - process HTTP request
        +---0 [22ms, 35%, 35%] - com.giants.auth.authority.api.AuthorityService.loadLoginEmployee
        `---22 [40ms, 63%, 63%] - com.giants.auth.gateway.conf.controller.SystemModuleController.search
            +---22 [19ms, 48%, 30%] - com.giants.auth.authority.api.AuthorityService.checkControllerAuthority
            `---41 [21ms, 52%, 33%] - com.giants.auth.conf.api.SystemModuleService.searchSystemModules
```

### 树形符号

- `+---`：非最后一个子节点。
- `` `--- ``：最后一个子节点。
- `|` / 缩进：表示层级归属关系。

### 每个节点的字段

以 `22 [40ms, 63%, 63%] - ...SystemModuleController.search` 为例：

| 字段 | 含义 |
| --- | --- |
| `22` | **相对起始时间**：相对于根 Entry 起点的毫秒数（即请求开始后第 22ms 进入该方法）。 |
| `40ms` | **总耗时**：该 Entry 从开始到结束的总时间。 |
| `(1ms)` | **自身耗时**（仅当与总耗时不同、且 > 0 时显示）：总耗时减去所有子节点耗时，代表该节点「自己」花的时间。 |
| 第一个 `%` | **父占比**：在父 Entry 总耗时中所占比例。 |
| 第二个 `%` | **全局占比**：在根 Entry 总耗时中所占比例。 |
| `- xxx` | Entry 的描述信息（HTTP 请求描述或方法名）。 |
| `[UNRELEASED]` | 该 Entry 尚未结束（通常意味着 `release` 未被正确调用）。 |

### 日志级别

| 级别 | 触发条件 |
| --- | --- |
| `INFO` | 耗时 ≤ `threshold` 且无异常。 |
| `WARN` | 耗时 > `threshold`（慢请求 / 慢方法）。 |
| `ERROR` | 执行过程中抛出异常。 |

> 排查性能问题时，重点关注**自身耗时占比高**的节点——那才是真正消耗时间的地方，而非仅仅总耗时高（后者可能只是其子调用慢）。

---

## 6. 最佳实践

- **生产环境默认关闭**：`logCallStackTimeAnalyse` 默认 `false`，避免持续的树构建与字符串拼接开销。需要排查时再打开。
- **阈值分级**：把 `threshold` 设为可接受的响应上限（如 500ms），这样日常只有慢请求进 `WARN`，便于监控告警聚焦。
- **谨慎开启 `showArguments`**：实参可能包含大对象、敏感信息（密码、令牌）或触发昂贵的 `toString`，仅在临时排查时开启，切勿长期用于生产。
- **组合使用**：Filter 建根 + AOP 补栈是可读性最好的方案；AOP 拦截层次按需选择（全拦截会让树很深，建议聚焦 Controller/Service/DAO 关键层）。
- **务必成对调用**：手动编码时，`start`/`enter` 必须与 `release` 成对，并在 `finally` 中 `reset`，防止 `ThreadLocal` 残留污染同一线程的后续请求（线程池复用场景尤其重要）。
- **注意异步/多线程**：计时基于 `ThreadLocal`，跨线程调用不会被自动串联。

---

## 7. 常见问题 FAQ

**Q：配置了 Filter 但没有任何耗时日志？**
A：确认 `logCallStackTimeAnalyse` 是否为 `true`。为 `false` 时 Filter 只透传请求、不计时也不输出。web.xml 方式无法直接设置该参数，请改用 Spring Bean 或带参构造函数。

**Q：AOP 拦截器不打印日志？**
A：只有充当「根」的拦截器（`logCallStackTimeAnalyse=true` 且当前线程尚无根 Entry）才输出日志。若已被 Filter 建根，或该拦截器设为 `false`，它只会静默追加子节点。

**Q：日志里出现 `[UNRELEASED]`？**
A：说明有 Entry 的 `release` 未被调用，通常是手动编码时 `enter` 与 `release` 不配对。请把 `release` 放到 `finally` 中。

**Q：方法名显示的是代理类而不是接口？**
A：拦截器已通过反射尽量还原接口名；若仍不理想，检查目标是否为标准的「接口 + 实现」结构，或 Bean 是否被多重代理。

**Q：能统计跨线程（异步任务、线程池）的耗时吗？**
A：不能自动统计。`ThreadLocal` 隔离决定了子线程需自行 `start` 一棵新树。

**Q：对性能有影响吗？**
A：关闭时（默认）几乎无开销；开启时开销主要来自树构建与日志字符串拼接，通常可忽略，但不建议在超高并发下长期全量开启。

---

## 8. API 速查表

### ExecutionTimeProfiler（静态方法）

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `start()` / `start(String)` | void | 创建根 Entry 并开始计时 |
| `enter(String)` | void | 在当前 Entry 下新增子 Entry |
| `release()` | void | 结束最近一个未结束的 Entry |
| `reset()` | void | 清空计时器（清理 ThreadLocal） |
| `getDuration()` | long | 根 Entry 总耗时（ms），未计时返回 -1 |
| `getEntry()` | Entry | 取得根 Entry，不存在返回 null |
| `dump()` / `dump(String)` / `dump(String,String)` | String | 格式化输出整棵时间树 |

### ExecutionTimeProfilerFilter

| 成员 | 说明 |
| --- | --- |
| `ExecutionTimeProfilerFilter()` | 默认构造 |
| `ExecutionTimeProfilerFilter(boolean, int)` | 指定 `logCallStackTimeAnalyse` 与 `threshold` |
| `setLogCallStackTimeAnalyse(boolean)` / `isLogCallStackTimeAnalyse()` | 开关方法栈分析 |
| `setThreshold(int)` / `getThreshold()` | 设置 / 读取阈值 |
| `init-param: thresholdPropertiesPath` | 从 properties 文件读取阈值（键 `profiler.executionTime.threshold`） |

### EnterExecutionTimeProfilerAop

| 成员 | 说明 |
| --- | --- |
| `timerProfiler(ProceedingJoinPoint)` | 环绕通知织入方法 |
| `setLogCallStackTimeAnalyse(boolean)` / `isLogCallStackTimeAnalyse()` | 是否充当根并输出日志 |
| `setThreshold(int)` | 设置阈值 |
| `setShowArguments(boolean)` | 是否打印方法实参 |

---

_本手册基于 giants-analyse 1.0.8 源码整理。_
