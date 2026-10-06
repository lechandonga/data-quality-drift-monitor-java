package com.github.lechandonga.dataqualitydriftmonitorjava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标注测试类所覆盖的业务域，输出到单测日志。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BusinessCoverage {
    String value();
}
