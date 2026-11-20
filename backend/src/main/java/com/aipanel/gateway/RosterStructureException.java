package com.aipanel.gateway;

/** 模型输出不符合运行时 JSON Schema 时抛出，属于可补偿的结构失败。 */
public class RosterStructureException extends RuntimeException {

    public RosterStructureException(String message) {
        super(message);
    }
}
