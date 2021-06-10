package com.giants.analyse.aop;

import java.text.MessageFormat;

import org.apache.commons.lang.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.giants.analyse.profiler.ExecutionTimeProfiler;
import com.giants.common.lang.reflect.ReflectUtils;

/**
 * @author vencent.lu
 *
 */
public class EnterExecutionTimeProfilerAop {
	
	protected final Logger loger = LoggerFactory.getLogger(this.getClass());

	private boolean logCallStackTimeAnalyse = false;
	private int threshold = 500;
	private boolean showArguments = false;
	
	public Object timerProfiler(ProceedingJoinPoint service) throws Throwable{	
		if (!this.logCallStackTimeAnalyse || ExecutionTimeProfiler.getEntry()!=null) {
			return this.enter(service);
		} else {
			return this.start(service);
		}
	}
	
	private Object enter(ProceedingJoinPoint service) throws Throwable{
		try{
			ExecutionTimeProfiler.enter(this.getMethod(service));
			Object result = service.proceed();
			return result;
		} catch (Throwable e) {
			throw e;
		} finally {
			ExecutionTimeProfiler.release();
		}
	}
	
	private Object start(ProceedingJoinPoint service) throws Throwable{
		String targetName = this.getMethod(service);		 
		 Throwable failed = null;
		 Object result = null;
		try {
			ExecutionTimeProfiler.start(targetName);
			result = service.proceed();
		} catch (Throwable e) {
			failed = e;
		} finally {
			ExecutionTimeProfiler.release();
			long duration = ExecutionTimeProfiler.getDuration();
			if (failed != null) {
				this.loger.error(MessageFormat.format(
						"Execute the {0} failed in {1,number}ms: {2}\n{3}\n",
						new Object[] { targetName, new Long(duration),
								failed.getLocalizedMessage(), getDetail() }));
			} else if (duration > threshold) {
				this.loger.warn(MessageFormat.format(
						"Execute the {0} returned in {1,number}ms\n{2}\n",
						new Object[] { targetName, new Long(duration),
								getDetail() }));
			} else {
				this.loger.info(MessageFormat.format(
						"Execute the {0} returned in {1,number}ms\n{2}\n",
						new Object[] { targetName, new Long(duration),
								getDetail() }));
			}
			ExecutionTimeProfiler.reset();
		}
		if (failed != null) {
			throw failed;
       }
       return result;
	}
	
	private String getMethod(ProceedingJoinPoint service)
			throws ClassNotFoundException {
		String targetName = service.getSignature().getDeclaringTypeName();
		String methodName = service.getSignature().getName();
		Object[] arguments = service.getArgs();
        
		Class<?> targetClass = service.getTarget().getClass();		
		if (!targetClass.getName().equals(targetName)
				&& ReflectUtils.getInterface(targetClass, targetName) == null) {
			String[] methodSignatures = service.getSignature().toLongString().split(" ");
			String[] parses = methodSignatures[methodSignatures.length-1].split("\\(");
			String argTypesStr = parses[1].replace(")", "");
			if (StringUtils.isNotEmpty(argTypesStr)) {
				String[] argTypeNames = argTypesStr.split("\\,");
				Class<?>[] parameterTypes = new Class<?>[argTypeNames.length];
				for (int i=0; i < argTypeNames.length; i++) {
					parameterTypes[i] = ReflectUtils.classForName(argTypeNames[i]);
				}
				Class<?> inter = ReflectUtils.findMethodInterface(service.getTarget()
						.getClass(), methodName, parameterTypes);
				if (inter != null) {
					targetName = inter.getName();
				}
			}
		}
      
        StringBuffer sb = new StringBuffer();
        sb.append(targetName).append(".").append(methodName);
        if (showArguments) {
            sb.append("(");
            if ((arguments != null) && (arguments.length != 0)) {
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(String.valueOf(arguments[i]));
                }
            }
            sb.append(")");
        }
        return sb.toString();
	}
	
	private String getDetail() {
        return ExecutionTimeProfiler.dump("Detail: ", "        ");
    }

	public void setShowArguments(boolean showArguments) {
		this.showArguments = showArguments;
	}
	
	public void setThreshold(int threshold) {
		this.threshold = threshold;
	}

	public boolean isLogCallStackTimeAnalyse() {
		return logCallStackTimeAnalyse;
	}

	public void setLogCallStackTimeAnalyse(boolean logCallStackTimeAnalyse) {
		this.logCallStackTimeAnalyse = logCallStackTimeAnalyse;
	}
}
