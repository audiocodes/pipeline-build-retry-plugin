package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.pipelinebuildretry.delay.FixedDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ListDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ProgressiveDelay;
import io.jenkins.plugins.pipelinebuildretry.steps.BuildRetryDecisionStep;
import io.jenkins.plugins.pipelinebuildretry.steps.BuildRetryPointOfNoReturnStep;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

class HelpFilesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static Path dir(Class<?> k) {
        return RESOURCES.resolve(k.getName().replace('.', '/'));
    }

    @Test
    void everyFormFieldHasHelp() throws IOException {
        List<String> missing = new ArrayList<>();
        Pattern field = Pattern.compile("field=\"([A-Za-z]+)\"");
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("config"))
                    .toList()) {
                Matcher m = field.matcher(Files.readString(f));
                while (m.find()) {
                    if (!Files.exists(f.resolveSibling("help-" + m.group(1) + ".html"))) {
                        missing.add(RESOURCES.relativize(f) + ": " + m.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyPipelineArgumentHasHelp() {
        List<String> missing = new ArrayList<>();
        for (Class<?> k : List.of(
                BuildRetryJobProperty.class,
                BuildRetryGlobalConfiguration.class,
                FixedDelay.class,
                ProgressiveDelay.class,
                ListDelay.class,
                BuildRetryPointOfNoReturnStep.class)) {
            for (Method m : k.getMethods()) {
                if (m.isAnnotationPresent(DataBoundSetter.class)) {
                    String name = m.getName().substring(3);
                    check(k, Character.toLowerCase(name.charAt(0)) + name.substring(1), missing);
                }
            }
            for (var c : k.getConstructors()) {
                if (c.isAnnotationPresent(DataBoundConstructor.class)) {
                    for (var p : c.getParameters()) {
                        check(k, p.getName(), missing);
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyStepAndOptionHasOverallHelp() {
        List<String> missing = new ArrayList<>();
        for (Class<?> k : List.of(
                BuildRetryJobProperty.class, BuildRetryDecisionStep.class, BuildRetryPointOfNoReturnStep.class)) {
            if (!Files.exists(dir(k).resolve("help.html"))) {
                missing.add(k.getSimpleName());
            }
        }
        assertEquals(List.of(), missing);
    }

    private static void check(Class<?> k, String name, List<String> missing) {
        if (!Files.exists(dir(k).resolve("help-" + name + ".html"))) {
            missing.add(k.getSimpleName() + "." + name);
        }
    }
}
