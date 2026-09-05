package io.cubercise.fsbrowser.root;

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
 * browsing the home directory is usually not what an operator wants. A configured
 * Root that does not exist (or is not a directory) fails startup loudly rather
 * than being created silently — a typo in the deployment config should never
 * materialise as an empty directory.
 */
@Configuration(proxyBeanMethods = false)
public class RootConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RootConfiguration.class);

    @Bean
    public Path root(@Value("${FSB_ROOT:}") String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            String home = System.getProperty("user.home");
            log.warn("FSB_ROOT is not set; defaulting the Root to the user home directory: {}", home);
            return Path.of(home).toAbsolutePath().normalize();
        }
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "Configured Root is not an existing directory: " + root + " (set FSB_ROOT to an existing directory)");
        }
        return root;
    }
}
