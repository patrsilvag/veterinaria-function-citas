package com.function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;

public class FunctionTest {

    @Test
    public void testFunctionInstancia() {

        Function function = new Function();

        assertNotNull(function);
    }

    @Test
    public void testRequestPost() {

        HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);

        ExecutionContext context = mock(ExecutionContext.class);

        when(context.getLogger()).thenReturn(Logger.getGlobal());

        when(request.getHttpMethod()).thenReturn(HttpMethod.POST);

        when(request.getBody()).thenReturn(Optional.empty());

        assertNotNull(request);
        assertNotNull(context);
    }
}
