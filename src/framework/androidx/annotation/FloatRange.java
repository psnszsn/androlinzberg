/* artzig stub. */
package androidx.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.ANNOTATION_TYPE, ElementType.PACKAGE, ElementType.TYPE_USE})
public @interface FloatRange { double from() default Double.NEGATIVE_INFINITY; double to() default Double.POSITIVE_INFINITY; boolean fromInclusive() default true; boolean toInclusive() default true; }
