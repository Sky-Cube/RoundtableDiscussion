package com.aipanel.config;

import java.util.List;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** 本地开发已配置模型时使用真实生成；显式 scripted 和测试环境可离线联调。 */
public final class GatewayConditions {
    private GatewayConditions() {
    }

    private static boolean real(Environment env) {
        String mode = env.getProperty("panel.gateway.mode", "real");
        return "real".equalsIgnoreCase(mode) || ("auto".equalsIgnoreCase(mode)
                && List.of("base-url", "api-key", "model").stream()
                        .allMatch(key -> StringUtils.hasText(env.getProperty("panel.gateway." + key))));
    }

    public static final class Real implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return real(context.getEnvironment());
        }
    }

    public static final class Scripted implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return env.acceptsProfiles(Profiles.of("test")) || !real(env);
        }
    }
}
