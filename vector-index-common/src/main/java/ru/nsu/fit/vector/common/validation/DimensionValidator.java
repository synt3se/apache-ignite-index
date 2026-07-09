package ru.nsu.fit.vector.common.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

@Component
public class DimensionValidator implements ConstraintValidator<ValidDimension, float[]> {

    @Value("${vector.dimension}")
    private int requiredDimension;

    @Override
    public boolean isValid(float[] vector, ConstraintValidatorContext context) {
        // Если массив null, возвращаем true, так как за проверку на null отвечает @NotNull
        if (vector == null) {
            return true;
        }

        if (vector.length != requiredDimension) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "incorrect vector dimension: " + vector.length + ", required: " + requiredDimension
            ).addConstraintViolation();
            return false;
        }
        return true;
    }
}