# giants-analyse
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.vencent-lu/giants-analyse/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.vencent-lu/giants-analyse)

JAVA代码分析工具类

## 方法栈调用时间日志输出

#### com.giants.analyse.filter.ExecutionTimeProfilerFilter
httpRequest 时间统计过滤器，参数说明如下：
* logCallStackTimeAnalyse
    * 类型 : boolean 
    * 默认值 : false
    * 说明 : 是否记录方法栈执行时间日志
* threshold
    * 类型 : int 
    * 单位 : ms
    * 默认值 : 500
    * 执行时间阈值，小于等于时间阈值打印`info`日志，大于时间阈值打印`warn`日志
    
#### com.giants.analyse.aop.EnterExecutionTimeProfilerAop
方法执行时间统计AOP拦截器，参数说明如下：
* logCallStackTimeAnalyse
    * 类型 : boolean 
    * 默认值 : false
    * 说明 : 是否记录方法栈执行时间日志
* threshold
    * 类型 : int 
    * 单位 : ms
    * 默认值 : 500
    * 说明 : 执行时间阈值，小于等于时间阈值打印`info`日志，大于时间阈值打印`warn`日志
* showArguments
    * 类型 : boolean
    * 默认值 : false
    * 说明 : 是否记录请求参数
    
## 日志输出示例
```
2021-11-01 17:36:44.770  INFO - [TID: N/A] - 21 --- [nio-9001-exec-8] c.g.a.f.ExecutionTimeProfilerFilter      : Response of POST /systemModule/search returned in 63ms
Detail: 0 [63ms (1ms), 100%] - process HTTP request
        +---0 [22ms, 35%, 35%] - com.giants.auth.authority.api.AuthorityService.loadLoginEmployee
        `---22 [40ms, 63%, 63%] - com.giants.auth.gateway.conf.controller.SystemModuleController.search
            +---22 [19ms, 48%, 30%] - com.giants.auth.authority.api.AuthorityService.checkControllerAuthority
            `---41 [21ms, 52%, 33%] - com.giants.auth.conf.api.SystemModuleService.searchSystemModules
```