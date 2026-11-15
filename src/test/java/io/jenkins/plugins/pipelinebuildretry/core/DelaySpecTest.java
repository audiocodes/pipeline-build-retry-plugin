package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DelaySpecTest {

    private static List<Integer> sequence(DelaySpec d, int n) {
        return IntStream.range(0, n).mapToObj(d::compute).toList();
    }

    @Test
    void progressive() {
        assertEquals(
                List.of(300, 900, 1800, 3000, 4500, 6300, 8400, 10800, 10800, 10800),
                sequence(DelaySpec.progressive(300, 10800), 10),
                "default progressive sequence");
        assertEquals(
                List.of(5, 15, 30, 50, 75, 90, 90),
                sequence(DelaySpec.progressive(5, 90), 7),
                "freestyle help example 5/90");
        assertEquals(66, DelaySpec.progressive(1, 0).compute(10), "max <= 0 means no cap");
        assertEquals(900, DelaySpec.compute(null, 1), "null delay uses the freestyle fallback");
        assertEquals(
                Integer.MAX_VALUE,
                DelaySpec.progressive(1000, 0).compute(Integer.MAX_VALUE - 5),
                "huge count saturates instead of overflowing");
        assertEquals(300, DelaySpec.progressive(300, 10800).compute(-3), "negative count treated as 0");
    }

    @Test
    void fixedAndList() {
        assertEquals(
                List.of(60, 60),
                List.of(DelaySpec.fixed(60).compute(0), DelaySpec.fixed(60).compute(5)));
        assertEquals(
                List.of(30, 90, 180, 180, 180),
                sequence(DelaySpec.list(List.of(30, 90, 180)), 5),
                "list repeats its last value");
        assertEquals(0, DelaySpec.list(List.of()).compute(2), "an empty list waits 0");
    }

    @Test
    void names() {
        assertEquals("fixed", DelaySpec.fixed(1).typeName());
        assertEquals("progressive", DelaySpec.defaultDelay().typeName());
        assertEquals("list", DelaySpec.list(List.of(1)).typeName());
    }

    @Test
    void valueSemantics() {
        assertEquals(DelaySpec.fixed(5), DelaySpec.fixed(5));
        assertEquals(DelaySpec.fixed(5).hashCode(), DelaySpec.fixed(5).hashCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.fixed(5), DelaySpec.fixed(6));
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.fixed(5), DelaySpec.list(List.of(5)));
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.progressive(1, 2), DelaySpec.progressive(1, 3));
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.progressive(1, 2), DelaySpec.progressive(2, 2));
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.list(List.of(1)), DelaySpec.list(List.of(2)));
        org.junit.jupiter.api.Assertions.assertNotEquals(DelaySpec.fixed(5), "fixed 5s");
        assertEquals(
                List.of(DelaySpec.Type.PROGRESSIVE, 0, 300, 10800),
                List.of(
                        DelaySpec.defaultDelay().getType(),
                                DelaySpec.defaultDelay().getDelay(),
                        DelaySpec.defaultDelay().getIncrement(),
                                DelaySpec.defaultDelay().getMax()));
        assertEquals(7, DelaySpec.fixed(7).getDelay());
        assertEquals("fixed 7s", DelaySpec.fixed(7).toString());
        assertEquals("progressive 1/2", DelaySpec.progressive(1, 2).toString());
        assertEquals("list [1, 2]", DelaySpec.list(List.of(1, 2)).toString());
        assertEquals(10, DelaySpec.progressive(10, 10).compute(5), "a delay at the cap stays at the cap");
        assertEquals(0, DelaySpec.progressive(0, 10).compute(5), "increment 0 waits 0");
    }
}
