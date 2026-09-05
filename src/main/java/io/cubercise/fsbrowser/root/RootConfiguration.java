package io.cubercise.fsbrowser.root;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single directory this app browses: the Root.
 *
 * <p>The Root is taken from the {@code FSB_ROOT} environment variable; when it is
 * unset the user home directory is used and a startup warning is logged, because
 * browsing the home directory is usually not what an operator wants.
 */
@Configuration(proxyBeanMethods = false)
public class RootConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RootConfiguration.class);

    @Bean
    public Path root(
            @Value("${FSB_ROOT:}") String fsbRootEnv,
            @Value("${fsb.root:}") String fsbRootProperty) {
        String configured = !fsbRootProperty.isBlank() ? fsbRootProperty : fsbRootEnv;
        if (configured.isBlank()) {
            String home = System.getProperty("user.home");
            log.warn("FSB_ROOT is not set; defaulting the Root to the user home directory: {}", home);
            return Path.of(home).toAbsolutePath().normalize();
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Configured Root directory cannot be created: " + root, e);
        }
        return root;
    }
}
