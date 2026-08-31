/* artzig: runtime presence for AnnotationValidations .class literals — the
 * platform annotations otherwise live only on the compile classpath. Compiled
 * by a separate javac pass (no --system: the sysmodules image owns the
 * package and rejects it from the main pass). */
package android.annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
@Retention(RetentionPolicy.CLASS)
public @interface Nullable {}
