package ru.nsu.fit.sberlab.vectorindex.vectorserver.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;

import ru.nsu.fit.sberlab.vectorindex.vectorserver.rest.IndexSearchBody;

/** Нечитаемое тело запроса — 400, а не 500. */
class GlobalExceptionHandlerTest {

    @Test
    void numbersOutsideTheirTypeInTheBodyAreABadRequest() {
        for (String body : new String[]{"{\"vector\":[1,0],\"k\":2147483648}",
                "{\"vector\":[1,0],\"k\":3,\"timeoutMs\":99999999999999999999}"}) {
            Throwable parse = catchThrowable(() -> new ObjectMapper().readValue(body, IndexSearchBody.class));
            assertThat(parse).as("Jackson rejects the overflow instead of wrapping it: " + body).isNotNull();

            var response = new GlobalExceptionHandler()
                    .handleUnreadable(new HttpMessageNotReadableException("unreadable body", parse, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "INVALID_REQUEST");
        }
    }
}
