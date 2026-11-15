package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import org.junit.jupiter.api.Test;

class ClassNamesTest {

    interface Marker {}

    static class Base implements Serializable {}

    static class Child extends Base implements Marker {}

    @Test
    void matchesClassSuperclassAndInterfaces() {
        Child c = new Child();
        assertTrue(ClassNames.isA(c, Child.class.getName()));
        assertTrue(ClassNames.isA(c, Base.class.getName()));
        assertTrue(ClassNames.isA(c, Marker.class.getName()));
        assertTrue(ClassNames.isA(c, Serializable.class.getName()), "interface of a superclass");
        assertTrue(ClassNames.isA(c, Object.class.getName()));
        assertFalse(ClassNames.isA(c, "com.example.Absent"));
        assertFalse(ClassNames.isA(null, Object.class.getName()));
    }
}
