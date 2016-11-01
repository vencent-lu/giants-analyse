/**
 * Project: giants-web
 * 
 * File Created at 2012-08-01
 * $Id$
 * 
 * Copyright 2011 giants.com Croporation Limited.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Igoolu Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with milanoo.com.
 */
package com.giants.analyse.filter;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.giants.analyse.profiler.ExecutionTimeProfiler;
import com.giants.web.filter.AbstractFilter;

/**
 * 记录servlet处理时间的filter。
 * <p>
 * <code>web.xml</code>配置文件格式如下：
 * 
 * <pre>
 * &lt;![CDATA[
 *  &lt;filter&gt;
 *  &lt;filter-name&gt;timer&lt;/filter-name&gt;
 *  &lt;filter-class&gt;com.alibaba.webx.filter.timer.TimerFilter&lt;/filter-class&gt;
 *  &lt;init-param&gt;
 *  &lt;param-name&gt;threshold&lt;/param-name&gt;
 *  &lt;param-value&gt;30000&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 *  &lt;/filter&gt;
 *  ]]&gt;
 * </pre>
 * 
 * </p>
 * <p>
 * 其中<code>threshold</code>参数表明超时阈值，如果servlet处理的总时间超过该值，则filter会以warning的方式记录该次操作。
 * </p>
 * 
 * @author vencent.lu
 *
 */
public class ExecutionTimeProfilerFilter extends AbstractFilter {
	private int threshold;

    /**
     * 初始化filter, 设置监视参数.
     * 
     * @throws ServletException 初始化失败
     */
	public void init() throws ServletException {
		String thresholdString = "30000";
		String thresholdPropertiesPath = this.findInitParameter(
				"thresholdPropertiesPath", "classpath*:/threshold.properties");
		try {
			Properties prop = new Properties();
			/**
			 * 需要改进，参照Spring实现
			 */
			prop.load(this.getClass().getClassLoader().getResourceAsStream(thresholdPropertiesPath.split(":")[1]));
			thresholdString = prop.getProperty("profiler.executionTime.threshold");
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		try {
			threshold = Integer.parseInt(thresholdString);
		} catch (NumberFormatException e) {
			threshold = 0;
		}

		if (threshold < 0) {
			throw new ServletException(MessageFormat.format(
					"Invalid init parameter for filter: threshold = {0}",
					new Object[] { thresholdString }));
		}

		log.info(MessageFormat.format(
				"Timer filter started with threshold {0,number}ms",
				new Object[] { new Integer(threshold) }));
	}

    /**
     * 执行filter.
     * 
     * @param request HTTP请求
     * @param response HTTP响应
     * @param chain filter链
     * @throws IOException 处理filter链时发生输入输出错误
     * @throws ServletException 处理filter链时发生的一般错误
     */
    public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 开始处理request, 并计时.
        String requestString = dumpRequest(request);

        if (log.isInfoEnabled()) {
            log.info("Started processing request: " + requestString);
        }

        ExecutionTimeProfiler.start("process HTTP request");

        Throwable failed = null;

        try {
            chain.doFilter(request, response);
        } catch (Throwable e) {
            failed = e;
        } finally {
        	ExecutionTimeProfiler.release();

            long duration = ExecutionTimeProfiler.getDuration();

			if (failed != null) {
				log.error(MessageFormat.format(
						"Response of {0} failed in {1,number}ms: {2}\n{3}\n",
						new Object[] { requestString, new Long(duration),
								failed.getLocalizedMessage(), getDetail() }));
			} else if (duration > threshold) {
				log.warn(MessageFormat.format(
						"Response of {0} returned in {1,number}ms\n{2}\n",
						new Object[] { requestString, new Long(duration),
								getDetail() }));
			} else {
				log.info(MessageFormat.format(
						"Response of {0} returned in {1,number}ms\n{2}\n",
						new Object[] { requestString, new Long(duration),
								getDetail() }));
			}

            ExecutionTimeProfiler.reset();
        }

        if (failed != null) {
            if (failed instanceof Error) {
                throw (Error) failed;
            } else if (failed instanceof RuntimeException) {
                throw (RuntimeException) failed;
            } else if (failed instanceof IOException) {
                throw (IOException) failed;
            } else if (failed instanceof ServletException) {
                throw (ServletException) failed;
            }
        }
    }

    private String getDetail() {
        return ExecutionTimeProfiler.dump("Detail: ", "        ");
    }
    /**
     * @return the threshold
     */
    public int getThreshold() {
        return threshold;
    }
    /**
     * @param threshold the threshold to set
     */
    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
}
