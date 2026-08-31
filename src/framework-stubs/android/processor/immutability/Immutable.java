/* artzig stub: immutability-processor annotations (compile-time only). */
package android.processor.immutability;
public @interface Immutable {
    @interface Ignore {
        String reason() default "";
    }
    @interface Policy {
        Exception[] exceptions() default {};
        enum Exception { FINAL_CLASSES_WITH_FINAL_FIELDS }
    }
}
