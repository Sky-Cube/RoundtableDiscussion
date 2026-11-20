package com.aipanel.support;

/** 测试专用事务故障，仅在 test profile 的故障注入钩子中抛出。 */
public class FaultInjectionException extends RuntimeException {

    public FaultInjectionException() {
        super("合成事务故障");
    }
}