/* artzig: runtime presence for AnnotationValidations .class literals. */
package android.annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
@Retention(RetentionPolicy.CLASS)
public @interface FloatRange {
    double from() default Double.NEGATIVE_INFINITY;
    double to() default Double.POSITIVE_INFINITY;
}
