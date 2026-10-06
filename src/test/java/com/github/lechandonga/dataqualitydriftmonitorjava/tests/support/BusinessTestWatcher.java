package com.github.lechandonga.dataqualitydriftmonitorjava.tests.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让单测日志按“业务域 → 用例 → 结果”输出，便于从运行日志中看出覆盖了哪些业务。
 */
public class BusinessTestWatcher implements BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(BusinessTestWatcher.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("【业务覆盖开始】{}#{} —— {}",
                context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName(),
                business(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "失败" : "通过";
        log.info("【业务覆盖结果】{} —— {}", context.getDisplayName(), result);
    }

    private String business(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        if (testClass.isAnnotationPresent(BusinessCoverage.class)) {
            return testClass.getAnnotation(BusinessCoverage.class).value();
        }
        return testClass.getSimpleName();
    }
}
