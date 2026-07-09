package ru.nsu.fit.vector.common.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = {DimensionValidator.class})
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDimension {
    String message() default "incorrect vector dimension";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}