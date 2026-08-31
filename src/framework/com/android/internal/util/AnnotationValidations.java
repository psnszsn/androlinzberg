/* artzig stub: codegen annotation validation — no-op. */
package com.android.internal.util;
import java.lang.annotation.Annotation;
public class AnnotationValidations {
    private AnnotationValidations() {}
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, Object value) {}
    // No generic Object+varargs overload: it is ambiguous against the
    // primitive overloads for codegen calls like (IntRange.class, null, int).
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, String value,
            Object... params) {}
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, Object[] value,
            Object... params) {}
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, int value,
            Object... params) {}
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, long value,
            Object... params) {}
    public static void validate(Class<? extends Annotation> annotation, Annotation ignored, float value,
            Object... params) {}
}
