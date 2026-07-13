package com.sentinelpay.risk.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.springframework.test.web.servlet.ResultMatcher;

public final class OpenApiContractSupport {

    public static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(OpenApiContractSupport.class.getResource("/openapi.yaml").toString())
            .build();

    private OpenApiContractSupport() {
    }

    public static ResultMatcher openApi() {
        return OpenApiValidationMatchers.openApi().isValid(VALIDATOR);
    }
}
