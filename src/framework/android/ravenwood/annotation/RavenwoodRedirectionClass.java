/* artzig stub: ravenwood test-infra annotation. */
package android.ravenwood.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface RavenwoodRedirectionClass {
    String value() default "";
    String reason() default "";
    Class<?>[] blockedBy() default {};
    int bug() default 0;
    
}
