package lombok.experimental;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on any field to make lombok build a standard hasser.
 * <p>
 * Example:
 * <pre>
 *     private &#64;Hasser Integer foo;
 * </pre>
 * <p>
 * will generate:
 * <p>
 * <pre>
 *     public boolean hasFoo() {
 *         return this.foo != null;
 *     }
 * </pre>
 * <p>
 * This annotation can also be applied to a class, in which case it'll be as if all non-static fields that don't already have
 * a {@code @Hasser} annotation have the annotation.
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface Hasser {
	/**
	 * If you want your hasser to be non-public, you can specify an alternate access level here.
	 */
	lombok.AccessLevel value() default lombok.AccessLevel.PUBLIC;
}
