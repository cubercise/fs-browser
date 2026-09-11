package io.cubercise.fsbrowser.mode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The Mode type's own rules: unset/blank config means read-only, the two
 * wire spellings parse exactly, and anything else must fail startup
 * (an ApplicationContextRunner proves the bean wiring fails loudly, not
 * just the parser).
 */
class ModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModeConfiguration.class);

    @Test
    void unsetOrBlankConfigMeansReadOnly() {
        assertThat(Mode.fromConfig(null)).isEqualTo(Mode.READ_ONLY);
        assertThat(Mode.fromConfig("")).isEqualTo(Mode.READ_ONLY);
        assertThat(Mode.fromConfig("   ")).isEqualTo(Mode.READ_ONLY);
    }

    @Test
    void parsesTheTwoExactSpellings() {
        assertThat(Mode.fromConfig("read-only")).isEqualTo(Mode.READ_ONLY);
        assertThat(Mode.fromConfig("read-write")).isEqualTo(Mode.READ_WRITE);
    }

    @Test
    void otherValuesAreRefusedExactlyNotFuzzyMatched() {
        // No case-folding, no trimming, no aliases: a typo'd deployment
        // variable must fail, not quietly mean something.
        for (String bad : new String[] {"readwrite", "READ-WRITE", " read-write", "writable", "ro", "rw", "true"}) {
            try {
                Mode.fromConfig(bad);
                throw new AssertionError("Expected fromConfig to reject: '" + bad + "'");
            } catch (IllegalStateException e) {
                assertThat(e.getMessage())
                        .contains("FSB_MODE")
                        .contains("read-only")
                        .contains("read-write")
                        .contains(bad);
            }
        }
    }

    @Test
    void invalidConfigFailsContextStartup() {
        contextRunner
                .withPropertyValues("FSB_MODE=sometimes")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("FSB_MODE must be 'read-only' or 'read-write'");
                });
    }

    @Test
    void validConfigsBootAndBlankMeansReadOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Mode.class)).isEqualTo(Mode.READ_ONLY);
        });
        contextRunner
                .withPropertyValues("FSB_MODE=read-write")
                .run(context -> assertThat(context.getBean(Mode.class)).isEqualTo(Mode.READ_WRITE));
    }

    @Test
    void wireNameIsTheConfigSpelling() {
        assertThat(Mode.READ_ONLY.wireName()).isEqualTo("read-only");
        assertThat(Mode.READ_WRITE.wireName()).isEqualTo("read-write");
    }
}
