/* artzig stub: codegen markers. */
package com.android.internal.util;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface DataClass {
    boolean genGetters() default false;
    boolean genSetters() default false;
    boolean genBuilder() default false;
    boolean genConstructor() default true;
    boolean genEqualsHashCode() default false;
    boolean genToString() default false;
    boolean genParcelable() default false;
    boolean genAidl() default false;
    boolean genConstDefs() default true;
    boolean genHiddenGetters() default false;
    boolean genHiddenSetters() default false;
    boolean genHiddenBuilder() default false;
    boolean genHiddenConstructor() default false;
    boolean genHiddenConstDefs() default false;
    boolean genCopyConstructor() default false;
    boolean genForEachField() default false;
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
    @interface Generated {
        long time() default 0;
        String codegenVersion() default "";
        String sourceFile() default "";
        String inputSignatures() default "";
        @Retention(RetentionPolicy.SOURCE)
        @Target({ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.TYPE})
        @interface Member {}
    }
    @Retention(RetentionPolicy.SOURCE)
    @interface Suppress { String[] value(); }
    @Retention(RetentionPolicy.SOURCE)
    @interface Each {}
    @Retention(RetentionPolicy.SOURCE)
    @interface MaySetToNull {}
    @Retention(RetentionPolicy.SOURCE)
    @interface PluralOf { String value(); }
    @Retention(RetentionPolicy.SOURCE)
    @interface ParcelWith { Class<?> value(); }
}
