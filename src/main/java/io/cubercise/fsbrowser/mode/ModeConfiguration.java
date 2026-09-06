package io.cubercise.fsbrowser.mode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deployment-wide Mode from the {@code FSB_MODE} environment
 * variable (read-only when unset). Validation lives in
 * {@link Mode#fromConfig(String)} so both production startup and tests share
 * one rule — the same "fail loudly on bad deployment config" stance
 * RootConfiguration takes for FSB_ROOT.
 */
@Configuration(proxyBeanMethods = false)
public class ModeConfiguration {

    @Bean
    public Mode mode(@Value("${FSB_MODE:}") String configuredMode) {
        return Mode.fromConfig(configuredMode);
    }
}
